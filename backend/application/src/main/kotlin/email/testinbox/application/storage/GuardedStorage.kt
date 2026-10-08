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
)

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
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
    ): GuardedIngestReport {
        require(copies.isNotEmpty()) { "an event with no candidate never reaches storage" }
        require(copies.all { it.bytes == bytesPerCopy }) { "every copy of an event has the same exact footprint" }
        ensureOpen()
        val trial = admitThroughBreaker()
        var trialEnded = trial == null
        try {
            val report = ingestHoldingSlot(bytesPerCopy, copies, persist)
            if (trial != null) {
                if (report.appended.isEmpty() && report.duplicates.isEmpty()) {
                    breaker.abandonTrial()
                } else {
                    breaker.close(trial)
                    metrics.breakerOpen(breaker.isOpen)
                }
                trialEnded = true
            }
            return report
        } finally {
            // A failure that tripped the breaker already ended the trial; any
            // other failure, an Error included, leaves it to the next caller.
            if (!trialEnded) breaker.abandonTrial()
        }
    }

    /**
     * Whether storage would refuse this event before T1 (latch, or a breaker
     * that is open and not yet due a trial), checked BEFORE recipients are
     * resolved. The SMTP reply for an unhealthy storage is then the same `451`
     * whatever the recipients are: known or not, inside a limit or not.
     */
    fun unavailable(): StorageUnavailableException? =
        latch.latched()?.let { reason ->
            metrics.latched(true)
            StorageUnavailableException(StorageUnavailableReason.LATCHED, "storage admission is latched: $reason")
        } ?: activation.violated()?.let { detail ->
            StorageUnavailableException(StorageUnavailableReason.ACTIVATION_VIOLATED, "storage activation invariant broken: $detail")
        } ?: if (breaker.isBlocked()) {
            StorageUnavailableException(StorageUnavailableReason.BREAKER_OPEN, "storage breaker open")
        } else {
            null
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
        persist: (List<CopyPlan>) -> Map<MessageId, Boolean>,
    ): GuardedIngestReport {
        val guard = SlotGuard(acquireSlot(copies))
        try {
            hook.afterSlot()
            val result = admit(bytesPerCopy, copies)
            hook.afterAdmission()
            val admittedIds = result.admitted.map { it.candidate.messageId }.toSet()
            val admitted = copies.filter { it.messageId in admittedIds }
            val refused = result.refused.associate { it.candidate.inboxId to it.reason }
            if (admitted.isEmpty()) {
                // §6a: nothing to commit, so the refusals get their own short transaction.
                if (refused.isNotEmpty()) {
                    transactions.required {
                        reservations.lockInboxes(refused.keys)
                        reservations.recordRefusals(refused)
                    }
                }
                return GuardedIngestReport(emptyList(), emptyList(), refused)
            }
            uploadAll(admitted, checkNotNull(result.t0), guard)
            hook.afterUploads()
            hook.beforeCommit()
            val outcomes = commit(bytesPerCopy, admitted, refused, persist)
            return GuardedIngestReport(
                appended = outcomes.filterValues { it }.keys.toList(),
                duplicates = outcomes.filterValues { !it }.keys.toList(),
                refused = refused,
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
                guard.poisoned =
                    try {
                        abandon(attempts, admitted, guard.slot)
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

    /** The breaker's real-event (quota) trial this event is, or null. */
    private fun admitThroughBreaker(): StorageBreaker.Admission.Trial? =
        when (val admission = breaker.admit()) {
            StorageBreaker.Admission.Closed -> {
                null
            }

            is StorageBreaker.Admission.Blocked -> {
                throw StorageUnavailableException(StorageUnavailableReason.BREAKER_OPEN, "storage breaker open (${admission.kinds})")
            }

            is StorageBreaker.Admission.Trial -> {
                val failed = probe(admission.kinds)
                if (failed != null) {
                    breaker.trip(failed)
                    throw StorageUnavailableException(
                        StorageUnavailableReason.BREAKER_OPEN,
                        "storage breaker half-open probe failed (${admission.kinds})",
                    )
                }
                if (admission.needsRealEvent) {
                    admission
                } else {
                    breaker.close(admission)
                    metrics.breakerOpen(breaker.isOpen)
                    null
                }
            }
        }

    /**
     * The half-open probes for every open kind except quota: a fresh
     * clock-offset measurement for a clock offset, a zero-risk witness write
     * for anything else. Returns the kind that failed, or null.
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
        val witnessed = kinds.filter { it != StorageBreaker.Kind.CLOCK_OFFSET && it != StorageBreaker.Kind.QUOTA }
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
    ) = try {
        val started = System.nanoTime()
        admission
            .admit(StorageAdmissionRequest(bytesPerCopy, copies.map { it.candidate }, node.nodeId, node.generation))
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
                                    StorageRefusalReason.INBOX_LIMIT -> StorageAdmissionOutcome.REFUSED_INBOX
                                    StorageRefusalReason.WORKSPACE_LIMIT -> StorageAdmissionOutcome.REFUSED_WORKSPACE
                                    StorageRefusalReason.SERVICE_CAPACITY -> StorageAdmissionOutcome.REFUSED_GLOBAL
                                },
                            )
                        }
                    }
                }
            }
    } catch (e: StorageAdmissionUnavailableException) {
        metrics.physicalFailure(PhysicalFailureKind.LOCK_TIMEOUT)
        throw StorageUnavailableException(StorageUnavailableReason.LOCK_TIMEOUT, "T1 could not take the admission lock", e)
    }

    /**
     * The event failed physically. Returns true when the slot had to be
     * poisoned (kept occupied), because an ambiguity could not be persisted.
     */
    private fun abandon(
        attempts: List<Attempt>,
        admitted: List<CopyPlan>,
        slot: WriteSlots.Slot,
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
                ambiguous.isNotEmpty() -> PhysicalFailureKind.AMBIGUOUS
                last == UploadOutcome.Refused(UploadRefusal.QUOTA) -> PhysicalFailureKind.QUOTA
                last == UploadOutcome.Refused(UploadRefusal.DENIED) -> PhysicalFailureKind.DEADLINE
                last == UploadOutcome.NotStarted -> PhysicalFailureKind.UNAVAILABLE
                else -> PhysicalFailureKind.UNAVAILABLE
            }
        metrics.physicalFailure(kind)
        when (kind) {
            PhysicalFailureKind.AMBIGUOUS -> breaker.trip(StorageBreaker.Kind.AMBIGUOUS)
            PhysicalFailureKind.QUOTA -> breaker.trip(StorageBreaker.Kind.QUOTA)
            PhysicalFailureKind.UNAVAILABLE -> breaker.trip(StorageBreaker.Kind.UNAVAILABLE)
            else -> Unit // a missed deadline is definitive and says nothing about storage health
        }
        if (breaker.isOpen) metrics.breakerOpen(true)
        // Every upload that started ended definitively: nothing can still
        // appear, so the capacity can go back now (§4).
        if (ambiguous.isEmpty()) releaseAbandoned(admitted)
        // The slot is already poisoned; a fatal Error is not swallowed.
        recordError?.let { throw it }
        return poisoned
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
                reservations.lockInboxes((admitted.map { it.candidate.inboxId } + refused.keys).toSet())
                reservations.recordRefusals(refused)
                val outcomes = persist(admitted)
                check(outcomes.keys == ids.toSet()) { "persist must report every admitted copy" }
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
