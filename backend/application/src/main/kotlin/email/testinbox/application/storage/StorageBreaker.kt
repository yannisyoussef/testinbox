package email.testinbox.application.storage

import java.time.Duration

/**
 * ADR-035 §8: the per-node storage circuit breaker. It is infrastructure,
 * never capacity. An open breaker answers `451` before the slot and before
 * T1: no reservation, no lock.
 *
 * It opens on any physical failure: an ambiguous upload, the quota `400`, an
 * unreachable storage, a `403` refusal of the fenced upload, or a clock offset
 * above `ε_max`. It remembers EVERY
 * kind it opened on until it closes, so a later trip of one kind (a clock
 * offset, say) never hides an earlier one (an ambiguous upload). After a
 * backoff (15 s, doubling to 2 min), ONE caller gets the half-open
 * [Admission.Trial], which must clear every open kind:
 * - a clock offset needs a fresh in-bound measurement;
 * - an unreachable, timed-out or erroring storage, or a `403` (revoked or
 *   rotated credentials, a bucket policy, a missed deadline), needs a
 *   zero-risk witness probe;
 * - quota needs the witness (a store at or over its hard quota refuses even a
 *   zero-byte write) and then a real event. MinIO's lagging quota can accept a
 *   probe while real uploads still fail (probes Q2–Q7), and a quota outcome is
 *   definitive, so the trial risks nothing in H;
 * - a full filesystem needs Ops evidence (a fresh observation with the
 *   operational reserve available) and then a real event: a zero-byte probe
 *   succeeds on a full filesystem (contract §8);
 * - **an ambiguous upload is treated as a possibly full filesystem**
 *   (Ops lab E9, issue #102: with every non-2xx answer withheld and the
 *   connection reset, ENOSPC is indistinguishable from any other ambiguity,
 *   and a witness-only recovery re-sent a real 13.7 MiB upload to a full
 *   store every backoff). Under an enforcing mode
 *   ([ambiguityNeedsEvidence]) its trial needs the same evidence as a full
 *   filesystem, and then the witness and a real event. Under OFF the first
 *   trial is the witness and a real event; if that real event is ambiguous
 *   again the breaker ESCALATES and from then on needs the evidence too —
 *   without a monitor, only a restart. So at a full store there is at most
 *   one real-upload trial without evidence, and none at all where the
 *   contract's monitor is a prerequisite.
 *
 * **Who runs the trial (ADR-025, 2026-11-06).** Open AND half-open are
 * answered before any recipient resolves: [admit] is the pre-resolution gate,
 * and the half-open [Admission.Trial] it hands out is the NON-TENANT proof
 * (the witness, a clock measurement, the evidence) run on that path, whatever
 * the event's recipients. A tenant event never runs it. When the proof
 * passes, the kinds a probe can prove close at once; the kinds that need a
 * real event ([Admission.Trial.needsRealEvent]) become proven, and the
 * real-event trial is CLAIMED at the gate too ([Admission.Proven]): one event
 * at a time, before resolution, while every other event gets the same
 * pre-resolution `451`. A claimant that stores nothing gives the claim back.
 * A failed proof is a failed trial: the doubled backoff, and the same `451`
 * for everyone.
 *
 * Every trial is bound to its trip generation: ending a trial of an older
 * generation ([abandonTrial], [close], [trip] with the trial) never ends,
 * closes or fails a newer one, so two trials can never run at once.
 *
 * A real-event claim is a LEASE ([claimLease], `L_claim`): resolution and rate
 * limiting have no per-event deadline, so a claimant stuck on a dead socket
 * could otherwise keep the gate shut for good. A claim older than the lease is
 * a failed trial: a new generation, the doubled backoff, the proof needed
 * again, and no new kind. The old claimant's later end is then stale.
 */
class StorageBreaker(
    private val initialBackoff: Duration = Duration.ofSeconds(15),
    private val maxBackoff: Duration = Duration.ofMinutes(2),
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * What reopens a `STORAGE_FULL` trip (filesystem-containment contract §8):
     * a full filesystem accepts a zero-byte probe, so only Ops evidence may.
     * The default never does: without a monitor, only a restart clears it.
     * Its evidence is read outside the breaker's lock; a failure counts as "no".
     */
    private val storageFullGate: StorageFullGate = StorageFullGate.NEVER,
    /**
     * Whether an `AMBIGUOUS` trip needs the [storageFullGate]'s evidence
     * before any trial, from its first trip: true under TENANT_LIMITS and
     * ALL, where a live monitor is an activation prerequisite (gate F).
     */
    private val ambiguityNeedsEvidence: Boolean = false,
    /** `L_claim`, the real-event claim's lease ([StorageProtocol.CLAIM_LEASE] and how it is derived). */
    private val claimLease: Duration = StorageProtocol.CLAIM_LEASE,
    /** Told of each expired claim (a counter: no ids). Called outside the lock. */
    private val onClaimExpired: () -> Unit = {},
) {
    /**
     * What the breaker opened on. `GuardedStorage` trips `UNAVAILABLE` for a
     * connection never established, `AMBIGUOUS` for EVERY non-definitive
     * upload outcome (a timeout, a lost connection, any readable 5xx, an
     * unexpected status), `DENIED` for a `403` refusal of the fenced upload
     * (`AccessDenied`, `SignatureDoesNotMatch`: credentials, policy or a
     * missed deadline, none of them about one recipient), `STORAGE_FULL`,
     * `QUOTA` and `CLOCK_OFFSET`; `TIMEOUT` and `SERVER_ERROR` are kept for the
     * metric vocabulary and are not tripped by anything today.
     */
    enum class Kind { UNAVAILABLE, TIMEOUT, SERVER_ERROR, AMBIGUOUS, QUOTA, CLOCK_OFFSET, STORAGE_FULL, DENIED }

    sealed interface Admission {
        /** Closed: proceed. */
        data object Closed : Admission

        /** Open, or a trial is already running elsewhere: answer `451`. */
        data class Blocked(
            val kinds: Set<Kind>,
        ) : Admission

        /**
         * Still open, but this trip generation's non-tenant proof passed, and
         * THIS caller now holds its real-event [trial]: it may go on to
         * resolve its recipients, and must end the trial ([close], [trip] or
         * [abandonTrial]) whether or not it reaches an upload. Only kinds that
         * need a real event ever get here.
         */
        data class Proven(
            val trial: Trial,
        ) : Admission

        /**
         * This caller runs the half-open trial for [kinds]. It must end in
         * [close], [trip] or [abandonTrial].
         */
        data class Trial(
            val kinds: Set<Kind>,
            /** The trip generation this trial was issued for. */
            val epoch: Long = 0,
        ) : Admission {
            /**
             * Whether the caller's real event is part of the trial: quota, a
             * full filesystem and an ambiguous upload all let a zero-byte probe
             * through, so only a real allocation through the normal reservation
             * path proves recovery.
             */
            val needsRealEvent: Boolean get() = Kind.QUOTA in kinds || Kind.STORAGE_FULL in kinds || Kind.AMBIGUOUS in kinds
        }
    }

    private val kinds = LinkedHashSet<Kind>()
    private var backoff = initialBackoff
    private var retryAt = 0L
    private var trialInFlight = false

    /** Bumped on every trip, so a trial only ever closes the trips it was issued for. */
    private var epoch = 0L

    /** An AMBIGUOUS real-event trial came back ambiguous: from here on, evidence or a restart. */
    private var escalated = false

    /**
     * This trip generation's non-tenant proof passed and its kinds await a
     * real event. Cleared by every trip and by close.
     */
    private var proven = false

    /** When the current real-event claim was taken ([nanoTime]); meaningful while [proven] and [trialInFlight]. */
    private var claimedAt = 0L

    /** A real-event claim is held past its lease. Call under the lock. */
    private fun claimExpired(): Boolean = proven && trialInFlight && nanoTime() - claimedAt - claimLease.toNanos() >= 0

    /** The held claim is a failed trial: a new generation, doubled backoff, the proof again. Call under the lock. */
    private fun expireClaim() {
        backoff = minOf(backoff.multipliedBy(2), maxBackoff)
        epoch++
        trialInFlight = false
        proven = false
        retryAt = nanoTime() + backoff.toNanos()
    }

    /** Whether the open kinds may reopen only on Ops evidence. Call under the lock. */
    private fun evidenceRequired(): Boolean =
        Kind.STORAGE_FULL in kinds || (Kind.AMBIGUOUS in kinds && (ambiguityNeedsEvidence || escalated))

    /**
     * The pre-resolution gate. [Admission.Trial] means the caller runs the
     * half-open proof NOW, before any recipient resolves, and ends it with
     * [proven], [trip] or [abandonTrial]. [Admission.Proven] means the caller
     * holds the real-event trial.
     */
    fun admit(): Admission {
        val evidence = evidenceIfNeeded()
        var expired = false
        try {
            synchronized(this) {
                if (claimExpired()) {
                    expireClaim()
                    expired = true
                    return Admission.Blocked(kinds.toSet())
                }
                return admitLocked(evidence)
            }
        } finally {
            if (expired) onClaimExpired()
        }
    }

    /** [admit] once the lease is settled. Call under the lock. */
    private fun admitLocked(evidence: Evidence?): Admission {
        if (kinds.isEmpty()) return Admission.Closed
        if (proven) {
            if (trialInFlight) return Admission.Blocked(kinds.toSet())
            trialInFlight = true
            claimedAt = nanoTime()
            return Admission.Proven(Admission.Trial(kinds.toSet(), epoch))
        }
        if (trialInFlight || nanoTime() - retryAt < 0) return Admission.Blocked(kinds.toSet())
        // No trial, and no trial consumed, while a full (or possibly full) filesystem has
        // no evidence of recovery FOR THIS trip generation: evidence read before a later
        // trip is void.
        if (evidenceRequired() && !evidence.holdsFor(epoch)) return Admission.Blocked(kinds.toSet())
        trialInFlight = true
        return Admission.Trial(kinds.toSet(), epoch)
    }

    /**
     * Whether [admit] would refuse a caller right now. Consumes no trial. A
     * half-open breaker is NOT blocked here (its next caller runs the proof),
     * which is why the pre-resolution gate is [admit], never this.
     */
    fun isBlocked(): Boolean {
        val evidence = evidenceIfNeeded()
        synchronized(this) {
            if (kinds.isEmpty()) return false
            // An expired claim is not a block: the next [admit] ends it as a failed trial.
            if (proven) return trialInFlight && !claimExpired()
            if (trialInFlight || nanoTime() - retryAt < 0) return true
            return evidenceRequired() && !evidence.holdsFor(epoch)
        }
    }

    /** An evidence answer, and the trip generation it was read for. */
    private data class Evidence(
        val epoch: Long,
        val holds: Boolean,
    )

    private fun Evidence?.holdsFor(current: Long): Boolean = this != null && holds && epoch == current

    /**
     * The evidence check, run OUTSIDE the lock (it reads the database), and
     * only when an evidence-gated trial could be due. Null means "not
     * checked": a trip that lands between the two looks counts as no
     * evidence, so the race can only keep the breaker shut.
     */
    private fun evidenceIfNeeded(): Evidence? {
        val generation =
            synchronized(this) {
                epoch.takeIf { evidenceRequired() && !proven && !trialInFlight && nanoTime() - retryAt >= 0 }
            } ?: return null
        val holds =
            try {
                storageFullGate.evidence()
            } catch (e: Exception) {
                false
            }
        return Evidence(generation, holds)
    }

    /**
     * A physical failure: open, or re-open after a failed trial with a doubled
     * backoff. [fromTrialEvent] says the trip comes from the REAL EVENT of the
     * trial in flight (the caller that holds the [Admission.Trial]); a failed
     * witness, or another event ending ambiguous while a trial runs, is not
     * that and never escalates — the witness proves nothing about fullness,
     * and a concurrent event is not the trial.
     */
    @Synchronized
    fun trip(
        kind: Kind,
        fromTrialEvent: Boolean = false,
    ) = tripLocked(kind, fromTrialEvent, failedTrial = kinds.isNotEmpty() && trialInFlight)

    /**
     * A physical failure met by the caller holding [trial]: its [realEvent]
     * (an upload), or its proof. Only a trial of the CURRENT generation fails
     * here: a stale one (a trip overtook it) is reported like any concurrent
     * event's failure, and never counts as the newer trial's failure, never
     * doubles its backoff and never escalates.
     */
    @Synchronized
    fun trip(
        kind: Kind,
        trial: Admission.Trial,
        realEvent: Boolean,
    ) {
        val current = trial.epoch == epoch && trialInFlight
        tripLocked(kind, fromTrialEvent = current && realEvent, failedTrial = current)
    }

    private fun tripLocked(
        kind: Kind,
        fromTrialEvent: Boolean,
        failedTrial: Boolean,
    ) {
        backoff =
            if (failedTrial) {
                minOf(backoff.multipliedBy(2), maxBackoff)
            } else if (kinds.isNotEmpty()) {
                backoff
            } else {
                initialBackoff
            }
        // The real event of an AMBIGUOUS trial was ambiguous again: a store that
        // accepts a zero-byte witness and swallows every large write is, for all
        // this node can tell, full. No more trials without evidence.
        val ambiguousAgain = kind == Kind.AMBIGUOUS && Kind.AMBIGUOUS in kinds
        if (ambiguousAgain && failedTrial && fromTrialEvent) escalated = true
        kinds += kind
        epoch++
        trialInFlight = false
        proven = false
        retryAt = nanoTime() + backoff.toNanos()
        // Every evidence-gated trip, a failed trial's included, invalidates every earlier observation.
        if (evidenceRequired() && (kind == Kind.STORAGE_FULL || kind == Kind.AMBIGUOUS)) storageFullGate.tripped()
    }

    /** Whether an ambiguous trip has escalated to needing evidence (for metrics, logs and tests). */
    @get:Synchronized
    val isEscalated: Boolean get() = escalated

    /** The kinds currently open, for metrics, logs and tests. */
    @get:Synchronized
    val openKinds: Set<Kind> get() = kinds.toSet()

    /**
     * The non-tenant proof of [trial] passed. Kinds a probe proves close now
     * ([Admission.Closed]). For kinds that need a real event the generation
     * becomes proven, and the prover keeps the trial as its real-event claim
     * ([Admission.Proven]). [Admission.Blocked] when a trip overtook the proof:
     * that trip already ended it, and may since have issued a newer trial.
     */
    @Synchronized
    fun proven(trial: Admission.Trial): Admission {
        if (trial.epoch != epoch) return if (kinds.isEmpty()) Admission.Closed else Admission.Blocked(kinds.toSet())
        if (!trial.needsRealEvent) {
            close()
            return Admission.Closed
        }
        proven = true // trialInFlight stays set: the prover now holds the real-event claim
        claimedAt = nanoTime()
        return Admission.Proven(trial)
    }

    /** Whether the proof passed and a real-event trial is due or running (for metrics, logs and tests). */
    @get:Synchronized
    val awaitsRealEvent: Boolean get() = proven

    /**
     * [trial] ended without an answer (its event stored nothing): the next
     * caller trials instead. A trial of an older generation frees nothing: a
     * trip already ended it, and the slot may belong to a newer trial.
     */
    @Synchronized
    fun abandonTrial(trial: Admission.Trial) {
        if (trial.epoch == epoch) trialInFlight = false
    }

    /**
     * [trial] succeeded. It closes the breaker only if nothing tripped since
     * it was issued: a quota trial is a whole real event, and an ambiguous
     * outcome or a clock offset reported meanwhile needs its own trial. A
     * stale trial touches nothing: the trip already ended it, and the slot
     * may belong to a newer trial.
     */
    @Synchronized
    fun close(trial: Admission.Trial) {
        if (trial.epoch == epoch) close()
    }

    /** Closes unconditionally (a success no later trip can have overtaken). */
    @Synchronized
    fun close() {
        kinds.clear()
        trialInFlight = false
        proven = false
        backoff = initialBackoff
        escalated = false
    }

    @get:Synchronized
    val isOpen: Boolean get() = kinds.isNotEmpty()

    @get:Synchronized
    val currentBackoff: Duration get() = backoff
}
