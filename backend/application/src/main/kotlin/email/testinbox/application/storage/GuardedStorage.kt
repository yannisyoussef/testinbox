package email.testinbox.application.storage

import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.DatabaseClock
import email.testinbox.application.port.PhysicalFailureKind
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.StorageAdmissionOutcome
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageCommitFence
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageLatch
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.port.UploadRefusal
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.application.usecase.StorageAdmissionDecision
import email.testinbox.application.usecase.StorageAdmissionRequest
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.storage.StorageRefusalReason
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/** This process's identity in `storage_node` and on every reservation it creates. */
data class StorageNode(
    val nodeId: String,
    val generation: UUID,
)

/**
 * One recipient copy's physical plan, fixed before T1: its admission
 * candidate (exact keys) and the exact bytes of each key, in the same order.
 */
class CopyPlan(
    val candidate: StorageAdmissionCandidate,
    val objects: List<Pair<String, ByteArray>>,
) {
    init {
        require(objects.map { it.first } == candidate.objectKeys) { "a copy's objects are exactly its candidate's keys, in order" }
    }

    val messageId: MessageId get() = candidate.messageId
    val bytes: Long get() = objects.sumOf { it.second.size.toLong() }
}

/** What the guarded path did with one event. */
data class GuardedIngestReport(
    val appended: List<MessageId>,
    val duplicates: List<MessageId>,
    /** Copies refused by an ENFORCED ceiling: stored nowhere, recorded on their inbox (§6a). */
    val refused: Map<InboxId, StorageRefusalReason>,
    /**
     * Copies whose participant workspace was inactive at T1 (account
     * lifecycle): never reserved, uploaded or recorded as a refusal, and never
     * a duplicate. Their inboxes, in envelope order.
     */
    val inactive: List<InboxId> = emptyList(),
)

/**
 * One event's pre-resolution decision ([GuardedStorage.admitEvent]): the
 * `451` it gets before any recipient resolves, or none. While the breaker
 * awaits a real event, an admitted event may also hold the breaker's
 * real-event trial, claimed at the gate; [GuardedStorage.ingest] consumes it,
 * and [GuardedStorage.release] gives it back if the event never reaches an
 * upload.
 */
class EventAdmission internal constructor(
    val refusal: StorageUnavailableException?,
    private var claim: StorageBreaker.Admission.Trial?,
) {
    /** The claimed trial, handed out once. */
    internal fun take(): StorageBreaker.Admission.Trial? = claim.also { claim = null }

    /** Whether this event holds the breaker's real-event trial (for tests). */
    val holdsRealEventTrial: Boolean get() = claim != null
}

/**
 * ADR-035's guarded ingest protocol for one inbound event (§4–§8):
 *
 * ```
 * latch? → breaker? → one fair write slot → T1 (reservations, database t0)
 *   → fenced uploads, one attempt per exact key, sequentially
 *   → T2: reservations FOR UPDATE, inboxes FOR KEY SHARE, refusal records,
 *         messages + notify, reservations consumed
 * ```
 *
 * Any physical failure (an upload that is not stored, a lock or slot timeout,
 * a fenced reservation) abandons the WHOLE event. Nothing becomes visible, and
 * the caller answers `451` so the sender retries. Before the slot is returned,
 * an ambiguous upload is persisted as ambiguity, and its reservation stays
 * charged until cleanup can prove its keys absent. Capacity is never
 * released inline for an ambiguous outcome.
 *
 * Its constructor takes one collaborator per protocol step: T1, the commit
 * fence, ambiguity, the latch, the fenced writer, inspection for the probe,
 * slots, the breaker, the node, transactions, the database clock, metrics and
 * the test hook. Grouping them behind a wrapper to satisfy a counter would
 * hide which step uses which.
 */
@Suppress("LongParameterList") // one collaborator per protocol step; see the class comment
class GuardedStorage(
    private val admission: StorageAdmission,
    private val reservations: StorageCommitFence,
    private val ambiguity: StorageAmbiguity,
    private val latch: StorageLatch,
    private val blobs: BlobStore,
    private val inspection: StorageInspection,
    private val slots: WriteSlots,
    private val breaker: StorageBreaker,
    private val node: StorageNode,
    private val transactions: TransactionRunner,
    private val clock: DatabaseClock,
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    private val hook: IngestSyncHook = IngestSyncHook.NONE,
    /** TI-STORAGE-006 §22: a non-OFF node whose activation invariant is broken fails closed here. */
    private val activation: email.testinbox.application.storage.activation.ActivationGuard =
        email.testinbox.application.storage.activation
            .ActivationGuard(),
    /** TI-STORAGE-006E PR D: under `ALL`, untrusted counts or an invalid observation fail closed here. */
    private val footprint: FootprintPrecheck = FootprintPrecheck.NONE,
) {
    private class Attempt(
        val messageId: MessageId,
        val key: String,
        val bytes: Long,
        val outcome: UploadOutcome,
    )

    /**
     * Runs [copies] (one per inbox, envelope order, all [bytesPerCopy] bytes)
     * through the protocol. [persist] runs INSIDE T2, after the reservation
     * fence, the inbox locks and the refusal records. It appends the admitted
     * copies' messages and says which ones were appended (true) or were
     * ADR-026 duplicates (false).
     */
    fun ingest(
        bytesPerCopy: Long,
        copies: List<CopyPlan>,
        envelopePositions: List<Int> = copies.indices.toList(),
        /**
         * The event's own [admitEvent], taken before its recipients resolved.
         * Null for a caller that did not take one: the gate is then passed here.
         */
        admission: EventAdmission? = null,
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
    ): GuardedIngestReport {
        require(copies.isNotEmpty()) { "an event with no candidate never reaches storage" }
        require(copies.all { it.bytes == bytesPerCopy }) { "every copy of an event has the same exact footprint" }
        val own = if (admission == null) admitEvent() else null
        try {
            (admission ?: own)?.refusal?.let { throw it }
            ensureOpen()
            return ingestAdmitted(bytesPerCopy, copies, envelopePositions, (admission ?: checkNotNull(own)).take(), persist)
        } finally {
            own?.let(::release)
        }
    }

    private fun ingestAdmitted(
        bytesPerCopy: Long,
        copies: List<CopyPlan>,
        envelopePositions: List<Int>,
        claim: StorageBreaker.Admission.Trial?,
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
    ): GuardedIngestReport {
        // Without the claim, only a closed breaker lets an event upload: one that passed the gate
        // while closed and met a trip (or a proof) since gets the 451 after resolution.
        if (claim == null && breaker.isOpen) {
            throw StorageUnavailableException(StorageUnavailableReason.BREAKER_OPEN, "storage breaker open")
        }
        var trialEnded = claim == null
        try {
            val report = ingestHoldingSlot(bytesPerCopy, copies, envelopePositions, persist, claim)
            if (claim != null) {
                if (report.appended.isEmpty() && report.duplicates.isEmpty()) {
                    breaker.abandonTrial(claim)
                } else {
                    breaker.close(claim)
                    metrics.breakerOpen(breaker.isOpen)
                    metrics.breakerEscalated(breaker.isEscalated)
                }
                trialEnded = true
            }
            return report
        } finally {
            // A failure that tripped the breaker already ended the trial (abandoning it
            // again is a no-op for its generation); any other failure, an Error
            // included, gives it back to the next caller.
            if (!trialEnded) breaker.abandonTrial(claim!!)
        }
    }

    /**
     * Whether storage refuses this event before T1 (latch, activation, an
     * open OR half-open breaker, slots, footprint), decided BEFORE recipients
     * are resolved. The SMTP reply for an unhealthy storage is then the same
     * `451` whatever the recipients are: known or not, inside a limit or not.
     *
     * A half-open breaker's trial runs HERE, on this pre-resolution path: the
     * non-tenant witness (and clock measurement) decides recovery, and a
     * real-event trial is claimed here too, so the answer during recovery
     * does not depend on the recipients either ([breakerGate]). The caller
     * must [release] the admission once the event is over.
     */
    fun admitEvent(): EventAdmission {
        val refusal =
            latch.latched()?.let { reason ->
                metrics.latched(true)
                StorageUnavailableException(StorageUnavailableReason.LATCHED, "storage admission is latched: $reason")
            } ?: activation.violated()?.let { detail ->
                StorageUnavailableException(StorageUnavailableReason.ACTIVATION_VIOLATED, "storage activation invariant broken: $detail")
            }
        if (refusal != null) return EventAdmission(refusal, null)
        val gate = breakerGate()
        if (gate.refusal != null) return gate
        val later =
            if (slots.exhaustedBeforeResolution()) {
                StorageUnavailableException(StorageUnavailableReason.SLOT_WAIT, "every write slot is held by unresolved ambiguity")
            } else {
                footprint.unavailable()?.let { reason ->
                    metrics.footprintUnavailable(reason)
                    StorageUnavailableException(
                        StorageUnavailableReason.FOOTPRINT_UNAVAILABLE,
                        "global footprint rules not evaluable: $reason",
                    )
                }
            }
        if (later == null) return gate
        release(gate)
        return EventAdmission(later, null)
    }

    /** The event is over: a real-event trial it claimed and never consumed goes back, for its generation only. */
    fun release(admission: EventAdmission) {
        admission.take()?.let(breaker::abandonTrial)
    }

    /**
     * The event's write slot, and whether an upload is in doubt: begun, with
     * an outcome neither definitive nor persisted as ambiguity. While one is,
     * the slot may NEVER be returned. Whatever ends the event, a throwable
     * included, poisons it instead (§9).
     */
    private class SlotGuard(
        val slot: WriteSlots.Slot,
    ) {
        var poisoned = false
        var inDoubt = false

        fun end() {
            when {
                // Already poisoned: poison() and close() are idempotent together.
                poisoned -> Unit

                // Something escaped between an upload's start and its ambiguity
                // being persisted: occupied for this process's life, and the
                // generation is never marked clean.
                inDoubt -> slot.poison()

                else -> slot.close()
            }
        }
    }

    private fun ingestHoldingSlot(
        bytesPerCopy: Long,
        copies: List<CopyPlan>,
        envelopePositions: List<Int>,
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
        /** The breaker's real-event trial this event holds, or null. */
        trial: StorageBreaker.Admission.Trial?,
    ): GuardedIngestReport {
        val guard = SlotGuard(acquireSlot(copies))
        try {
            hook.afterSlot()
            val result = admit(bytesPerCopy, copies, envelopePositions)
            hook.afterAdmission()
            val admittedIds = result.admitted.map { it.candidate.messageId }.toSet()
            val admitted = copies.filter { it.messageId in admittedIds }
            val refused = result.refused.associate { it.candidate.inboxId to it.reason }
            val inactive = result.inactive.map { it.candidate.inboxId }
            if (admitted.isEmpty()) {
                // §6a: nothing to commit, so the refusals get their own short transaction.
                if (refused.isNotEmpty()) {
                    transactions.required {
                        reservations.lockInboxes(refused.keys)
                        reservations.recordRefusals(refused)
                    }
                }
                return GuardedIngestReport(emptyList(), emptyList(), refused, inactive)
            }
            uploadAll(admitted, checkNotNull(result.t0), guard, trial)
            hook.afterUploads()
            hook.beforeCommit()
            val outcomes = commit(bytesPerCopy, admitted, refused, persist)
            return GuardedIngestReport(
                appended = outcomes.filterValues { it }.keys.toList(),
                duplicates = outcomes.filterValues { !it }.keys.toList(),
                refused = refused,
                inactive = inactive,
            )
        } finally {
            guard.end()
        }
    }

    /** Every object of every admitted copy, in order; throws on the first one not Stored. */
    private fun uploadAll(
        admitted: List<CopyPlan>,
        t0: java.time.Instant,
        guard: SlotGuard,
        trial: StorageBreaker.Admission.Trial?,
    ) {
        val attempts = mutableListOf<Attempt>()
        for (copy in admitted) {
            if (!reservations.markUploadStarted(copy.messageId)) {
                // Cleanup fenced this reservation before its first byte:
                // nothing of this copy was written. Whatever earlier copies
                // stored was stored definitively, so all of it can go back.
                metrics.commitFenced()
                releaseAbandoned(admitted)
                throw StorageUnavailableException(StorageUnavailableReason.COMMIT_FENCED, "a reservation was fenced before its upload")
            }
            for ((key, bytes) in copy.objects) {
                hook.beforeUpload(key)
                guard.inDoubt = true
                val (outcome, fatal) = put(ReservedUpload(key, bytes, t0, StorageProtocol.WRITE_WINDOW))
                attempts += Attempt(copy.messageId, key, bytes.size.toLong(), outcome)
                if (outcome == UploadOutcome.Stored) {
                    guard.inDoubt = false
                    continue
                }
                // A 403 after the copy's write deadline is the deadline, not storage (fix 2, below).
                val deniedInWindow = outcome == UploadOutcome.Refused(UploadRefusal.DENIED) && refusedBeforeDeadline(t0)
                guard.poisoned =
                    try {
                        abandon(attempts, admitted, guard.slot, trial, deniedInWindow)
                    } catch (t: Throwable) {
                        // The upload's own Error stays the primary one.
                        fatal?.let {
                            it.addSuppressed(t)
                            throw it
                        }
                        throw t
                    }
                guard.inDoubt = false // persisted, or the slot is poisoned
                // A fatal Error goes on up once the ambiguity is safe.
                fatal?.let { throw it }
                throw StorageUnavailableException(StorageUnavailableReason.UPLOAD_FAILED, "an upload was not stored: $outcome")
            }
        }
    }

    /**
     * One fenced upload. Anything but an outcome, once the call began, may
     * have reached storage, so it is ambiguous: an Exception and an Error
     * alike. An Error is handed back too, to be rethrown once the ambiguity
     * is safe; it is never swallowed.
     */
    @Suppress("TooGenericExceptionCaught") // deliberately every Throwable: see above
    private fun put(upload: ReservedUpload): Pair<UploadOutcome, Error?> =
        try {
            blobs.putReserved(upload) to null
        } catch (e: Throwable) {
            log.warn("fenced upload failed unexpectedly; treated as ambiguous: {}", e.javaClass.simpleName)
            UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE) to (e as? Error)
        }

    /**
     * Whether a `403` came while the copy's write window was still open
     * (database clock `< t0 + E`, the reservation's `write_deadline_at`).
     * Inside it, the refusal is about credentials or policy; at or after it,
     * the URL simply expired. Unknown (the clock cannot be read) counts as
     * inside: the breaker's uniform `451` is the safe side.
     */
    private fun refusedBeforeDeadline(t0: java.time.Instant): Boolean =
        runCatching { clock.now().isBefore(t0.plus(StorageProtocol.WRITE_WINDOW)) }.getOrDefault(true)

    private fun releaseAbandoned(admitted: List<CopyPlan>) {
        runCatching { reservations.releaseAbandoned(admitted.map { it.messageId }) }
            .onFailure {
                log.warn("could not release abandoned reservations; cleanup expires them at their deadline: {}", it.toString())
            }
    }

    private fun ensureOpen() {
        latch.latched()?.let { reason ->
            metrics.latched(true)
            throw StorageUnavailableException(StorageUnavailableReason.LATCHED, "storage admission is latched: $reason")
        }
        activation.violated()?.let { detail ->
            throw StorageUnavailableException(StorageUnavailableReason.ACTIVATION_VIOLATED, "storage activation invariant broken: $detail")
        }
    }

    /**
     * The breaker, decided before any recipient resolves (ADR-025). An open
     * breaker, or one whose trial is running, refuses. A half-open one hands
     * THIS caller its trial, and the trial is the non-tenant proof ([probe])
     * run right here, never a tenant event: so every event of the half-open
     * window, known recipients or not, inside a limit or not, gets the same
     * answer. A failed proof re-trips with the doubled backoff; a passed one
     * closes the kinds a probe can prove. Quota, a full filesystem and an
     * ambiguous upload keep their real-event trial (ADR-035 §8, issue #102),
     * and it is claimed HERE too, by one event at a time: every other event
     * gets the same pre-resolution `451` until the claimant's event is over.
     */
    private fun breakerGate(): EventAdmission =
        when (val admission = breaker.admit()) {
            StorageBreaker.Admission.Closed -> EventAdmission(null, null)
            is StorageBreaker.Admission.Proven -> EventAdmission(null, admission.trial)
            is StorageBreaker.Admission.Blocked -> EventAdmission(breakerOpen(), null)
            is StorageBreaker.Admission.Trial -> proveRecovery(admission)
        }

    private fun breakerOpen() = StorageUnavailableException(StorageUnavailableReason.BREAKER_OPEN, "storage breaker open")

    /** The half-open proof of [trial], before resolution. Ends the trial, or hands it on as the real-event claim. */
    private fun proveRecovery(trial: StorageBreaker.Admission.Trial): EventAdmission {
        var ended = false
        try {
            val failed = probe(trial.kinds)
            val outcome =
                if (failed != null) {
                    // A failed witness is never the real event: it does not escalate (issue #102).
                    breaker.trip(failed, trial, realEvent = false)
                    EventAdmission(breakerOpen(), null)
                } else {
                    when (val after = breaker.proven(trial)) {
                        StorageBreaker.Admission.Closed -> EventAdmission(null, null)
                        is StorageBreaker.Admission.Proven -> EventAdmission(null, after.trial)
                        else -> EventAdmission(breakerOpen(), null)
                    }
                }
            ended = true
            metrics.breakerOpen(breaker.isOpen)
            metrics.breakerEscalated(breaker.isEscalated)
            return outcome
        } finally {
            // Only an Error escaping the probe gets here unended: the next caller proves instead.
            if (!ended) breaker.abandonTrial(trial)
        }
    }

    /**
     * The half-open probes: a fresh clock-offset measurement for a clock
     * offset, a zero-risk witness write for every other kind except a full
     * filesystem (Ops evidence, read by the breaker, is its non-tenant proof:
     * a zero-byte write succeeds on a full filesystem, contract §8). Quota is
     * witnessed too: a store at or over its hard quota refuses even a
     * zero-byte write, and its real-event trial still follows. Returns the
     * kind that failed, or null.
     */
    private fun probe(kinds: Set<StorageBreaker.Kind>): StorageBreaker.Kind? {
        fun passes(check: () -> Boolean) =
            runCatching(check).getOrElse {
                log.warn("storage breaker probe failed: {}", it.toString())
                false
            }
        if (StorageBreaker.Kind.CLOCK_OFFSET in kinds && !passes { ClockOffset.measure(inspection, clock).withinBound() }) {
            return StorageBreaker.Kind.CLOCK_OFFSET
        }
        // A full filesystem accepts a zero-byte probe: evidence, then the real event, is its proof.
        val witnessed = kinds.filter { it != StorageBreaker.Kind.CLOCK_OFFSET && it != StorageBreaker.Kind.STORAGE_FULL }
        if (witnessed.isNotEmpty() && !passes { inspection.witness("_probe/${node.nodeId}/${UUID.randomUUID()}") }) {
            return witnessed.first()
        }
        return null
    }

    private fun acquireSlot(copies: List<CopyPlan>): WriteSlots.Slot {
        val started = System.nanoTime()
        try {
            return slots.acquire(copies.map { it.candidate.workspaceId }.toSet())
        } catch (e: StorageUnavailableException) {
            metrics.physicalFailure(PhysicalFailureKind.SLOT_WAIT)
            throw e
        } finally {
            metrics.slotWait(Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun admit(
        bytesPerCopy: Long,
        copies: List<CopyPlan>,
        envelopePositions: List<Int>,
    ) = try {
        val started = System.nanoTime()
        admission
            .admit(StorageAdmissionRequest(bytesPerCopy, copies.map { it.candidate }, node.nodeId, node.generation, envelopePositions))
            .also { result ->
                metrics.lockWait(Duration.ofNanos(System.nanoTime() - started))
                result.decisions.forEach { decision ->
                    when (decision) {
                        is StorageAdmissionDecision.Admitted -> {
                            metrics.admission(StorageAdmissionOutcome.ADMITTED)
                            decision.unenforcedLimit?.let { metrics.unenforcedLimit(it.scope) }
                        }

                        is StorageAdmissionDecision.Refused -> {
                            metrics.admission(
                                when (decision.reason) {
                                    StorageRefusalReason.INBOX_LIMIT -> {
                                        StorageAdmissionOutcome.REFUSED_INBOX
                                    }

                                    StorageRefusalReason.WORKSPACE_LIMIT, StorageRefusalReason.WORKSPACE_FOOTPRINT_LIMIT -> {
                                        StorageAdmissionOutcome.REFUSED_WORKSPACE
                                    }

                                    StorageRefusalReason.SERVICE_CAPACITY -> {
                                        StorageAdmissionOutcome.REFUSED_GLOBAL
                                    }
                                },
                            )
                        }

                        is StorageAdmissionDecision.Inactive -> {
                            metrics.admission(StorageAdmissionOutcome.INACTIVE_DISCARDED)
                        }
                    }
                }
            }
    } catch (e: StorageAdmissionUnavailableException) {
        metrics.physicalFailure(PhysicalFailureKind.LOCK_TIMEOUT)
        throw StorageUnavailableException(StorageUnavailableReason.LOCK_TIMEOUT, "T1 could not take the admission lock", e)
    } catch (e: StorageFootprintUnavailableException) {
        // The pre-resolution check passed and T1's own snapshot disagrees: the
        // documented race, answered with the same whole-event 451.
        metrics.footprintUnavailable(e.reason)
        throw StorageUnavailableException(StorageUnavailableReason.FOOTPRINT_UNAVAILABLE, "T1 found the footprint rules not evaluable", e)
    }

    /**
     * The event failed physically. Returns true when the slot had to be
     * poisoned (kept occupied), because an ambiguity could not be persisted.
     */
    private fun abandon(
        attempts: List<Attempt>,
        admitted: List<CopyPlan>,
        slot: WriteSlots.Slot,
        trial: StorageBreaker.Admission.Trial?,
        /** The last outcome is a `403` met before the write deadline. */
        deniedInWindow: Boolean,
    ): Boolean {
        val ambiguous = attempts.filter { !it.outcome.definitive }
        // Ambiguous: the reservation stays RESERVED and charged until cleanup
        // proves its keys absent, and the slot stays occupied until the
        // ambiguity is verified. The ambiguity is persisted FIRST, before
        // anything else can fail, and before the slot can be reused.
        var recordError: Error? = null
        val poisoned =
            try {
                ambiguous.forEach { ambiguity.record(node.nodeId, it.key, it.bytes, StorageProtocol.T_VERIFY) }
                false
            } catch (e: Throwable) {
                log.error("could not persist an ambiguous upload; its write slot stays occupied until this process ends", e)
                slot.poison()
                if (e is Error) recordError = e
                true
            }
        val last = attempts.last().outcome
        val kind =
            when {
                // ENOSPC is ambiguous for the reservation, but its own breaker kind (contract §8).
                ambiguous.any { it.outcome == UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL) } -> PhysicalFailureKind.STORAGE_FULL

                ambiguous.isNotEmpty() -> PhysicalFailureKind.AMBIGUOUS

                last == UploadOutcome.Refused(UploadRefusal.QUOTA) -> PhysicalFailureKind.QUOTA

                // A 403 refuses the fenced upload itself (credentials, policy, or the deadline): the metric
                // keeps its ADR-035 §16 label; the breaker trips only inside the write window (below).
                last == UploadOutcome.Refused(UploadRefusal.DENIED) -> PhysicalFailureKind.DEADLINE

                last == UploadOutcome.NotStarted -> PhysicalFailureKind.UNAVAILABLE

                else -> PhysicalFailureKind.UNAVAILABLE
            }
        metrics.physicalFailure(kind)
        val escalatedBefore = breaker.isEscalated

        // Only the trial's own real event, of the current generation, may fail the trial or escalate
        // an ambiguity (issue #102); a stale trial's trip is any concurrent event's.
        fun trip(breakerKind: StorageBreaker.Kind) =
            if (trial != null) breaker.trip(breakerKind, trial, realEvent = true) else breaker.trip(breakerKind)
        when (kind) {
            PhysicalFailureKind.AMBIGUOUS -> trip(StorageBreaker.Kind.AMBIGUOUS)

            PhysicalFailureKind.STORAGE_FULL -> trip(StorageBreaker.Kind.STORAGE_FULL)

            PhysicalFailureKind.QUOTA -> trip(StorageBreaker.Kind.QUOTA)

            PhysicalFailureKind.UNAVAILABLE -> trip(StorageBreaker.Kind.UNAVAILABLE)

            // A 403 inside the write window: revoked or rotated credentials, a bucket policy. Nothing
            // about the recipient. Untripped, only events that reach an upload (known, under-ceiling
            // recipients) would get this 451, for as long as it lasts (ADR-025). Tripped, it is the
            // pre-resolution 451 for everyone until the witness proves storage writable again, with the
            // normal backoff, so one spurious 403 costs one backoff, never a latch. At or after the
            // deadline the URL merely expired (one slow upload): that event's own 451, no trip.
            PhysicalFailureKind.DEADLINE -> if (deniedInWindow) trip(StorageBreaker.Kind.DENIED)

            else -> Unit
        }
        if (breaker.isOpen) metrics.breakerOpen(true)
        noteEscalation(escalatedBefore)
        // Every upload that started ended definitively: nothing can still
        // appear, so the capacity can go back now (§4).
        if (ambiguous.isEmpty()) releaseAbandoned(admitted)
        // The slot is already poisoned; a fatal Error is not swallowed.
        recordError?.let { throw it }
        return poisoned
    }

    /** Issue #102: an escalation is a stable `451` that only evidence or a restart clears, so it is said once, loudly. */
    private fun noteEscalation(escalatedBefore: Boolean) {
        metrics.breakerEscalated(breaker.isEscalated)
        if (breaker.isEscalated && !escalatedBefore) {
            log.error(
                "storage_breaker_escalated the real-event trial of an ambiguous trip was ambiguous again; this node treats " +
                    "storage as possibly full and reopens only on a filesystem observation showing the reserve (or a restart)",
            )
        }
    }

    private fun commit(
        bytesPerCopy: Long,
        admitted: List<CopyPlan>,
        refused: Map<InboxId, StorageRefusalReason>,
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
    ): Map<MessageId, Boolean> {
        val ids = admitted.map { it.messageId }
        try {
            return transactions.required {
                val locked = reservations.lockForCommit(ids)
                hook.inCommitAfterReservationLock()
                // I4: a message becomes visible only in the transaction that
                // consumes its RESERVED reservation, for exactly the reserved bytes.
                if (locked.size != ids.size || locked.any { it.state != "RESERVED" || it.bytes != bytesPerCopy }) {
                    throw StorageUnavailableException(
                        StorageUnavailableReason.COMMIT_FENCED,
                        "a reservation was no longer RESERVED at commit",
                    )
                }
                // TI-STORAGE-006E: a copy is committed only for exactly the keys T1
                // reserved, or an extra uploaded key would be live and charged nowhere.
                val keysById = admitted.associate { it.messageId to it.candidate.objectKeys }
                if (locked.any { it.objectKeys.toSet() != keysById.getValue(it.messageId).toSet() }) {
                    throw StorageUnavailableException(
                        StorageUnavailableReason.COMMIT_FENCED,
                        "a copy's keys differ from the keys its reservation holds",
                    )
                }
                val states = reservations.lockInboxes((admitted.map { it.candidate.inboxId } + refused.keys).toSet())
                // The contract's T2 fence: under FOR SHARE, no retention state change can
                // commit meanwhile, and a copy whose inbox already stopped receiving
                // (retention committed EXPIRED, then deleted its prefix while this upload
                // was in flight) must not become a live object no row charges.
                if (admitted.any { states[it.candidate.inboxId] !in RECEIVABLE }) {
                    throw StorageUnavailableException(
                        StorageUnavailableReason.COMMIT_FENCED,
                        "an admitted copy's inbox no longer receives",
                    )
                }
                reservations.recordRefusals(refused)
                val outcomes = persist(admitted)
                check(outcomes.keys == ids.toSet()) { "persist must report every admitted copy" }
                // The contract's T2 assertion: the rows just inserted name exactly the reserved
                // keys. A key uploaded but not committed would be a live object no row charges.
                val reserved = locked.associate { it.messageId to it.objectKeys.toSet() }
                val committed = reservations.committedKeys(outcomes.filterValues { it }.keys)
                if (outcomes.filterValues { it }.keys.any { committed[it].orEmpty() != reserved.getValue(it) }) {
                    throw StorageUnavailableException(
                        StorageUnavailableReason.COMMIT_FENCED,
                        "the committed rows do not name exactly the reserved keys",
                    )
                }
                reservations.consume(outcomes.filterValues { it }.keys)
                reservations.releaseDuplicates(outcomes.filterValues { !it }.keys)
                outcomes
            }
        } catch (e: StorageUnavailableException) {
            if (e.reason == StorageUnavailableReason.COMMIT_FENCED) metrics.commitFenced()
            releaseAbandoned(admitted)
            throw e
        } catch (e: RuntimeException) {
            // T2 failed (a deadlock loser, a vanished inbox, a database blip).
            // Every upload was Stored, definitively: nothing can still appear,
            // so the reservations go back now instead of at deadline + S.
            // Guarded on RESERVED, so a T2 that did commit is untouched.
            releaseAbandoned(admitted)
            throw e
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(GuardedStorage::class.java)

        /** The inbox states a copy may still be committed into (contract §2.4, T2 row). */
        val RECEIVABLE = setOf(email.testinbox.domain.inbox.InboxState.ACTIVE, email.testinbox.domain.inbox.InboxState.EXPIRING)
    }
}

/** A DB↔storage clock-offset measurement (ADR-035 §5). */
data class ClockOffset(
    val offset: Duration,
    /** `Date` resolution (1 s) plus the round trip. */
    val error: Duration,
) {
    /** Conservative: the offset may be as large as `|offset| + error`. */
    fun withinBound(bound: Duration = StorageProtocol.EPSILON_MAX): Boolean = offset.abs().plus(error) <= bound

    companion object {
        fun measure(
            inspection: StorageInspection,
            database: DatabaseClock,
        ): ClockOffset {
            val before = database.now()
            val server = inspection.serverTime()
            val after = database.now()
            val window = Duration.between(before, after)
            val midpoint = before.plus(window.dividedBy(2))
            return ClockOffset(Duration.between(midpoint, server.date), Duration.ofSeconds(1).plus(window))
        }
    }
}
