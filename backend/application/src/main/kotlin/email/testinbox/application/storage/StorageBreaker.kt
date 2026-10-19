package email.testinbox.application.storage

import java.time.Duration

/**
 * ADR-035 §8: the per-node storage circuit breaker. It is infrastructure,
 * never capacity. An open breaker answers `451` before the slot and before
 * T1: no reservation, no lock.
 *
 * It opens on any physical failure: an ambiguous upload, the quota `400`, an
 * unreachable storage, or a clock offset above `ε_max`. It remembers EVERY
 * kind it opened on until it closes, so a later trip of one kind (a clock
 * offset, say) never hides an earlier one (an ambiguous upload). After a
 * backoff (15 s, doubling to 2 min), ONE caller gets the half-open
 * [Admission.Trial], which must clear every open kind:
 * - a clock offset needs a fresh in-bound measurement;
 * - an unreachable, timed-out or erroring storage needs a zero-risk witness
 *   probe;
 * - quota needs a real event, after the probes pass. MinIO's lagging quota
 *   can accept a probe while real uploads still fail (probes Q2–Q7), and a
 *   quota outcome is definitive, so the trial risks nothing in H;
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
) {
    /**
     * What the breaker opened on. `GuardedStorage` trips `UNAVAILABLE` for a
     * connection never established, `AMBIGUOUS` for EVERY non-definitive
     * upload outcome (a timeout, a lost connection, any readable 5xx, an
     * unexpected status), `STORAGE_FULL`, `QUOTA` and `CLOCK_OFFSET`;
     * `TIMEOUT` and `SERVER_ERROR` are kept for the metric vocabulary and are
     * not tripped by anything today.
     */
    enum class Kind { UNAVAILABLE, TIMEOUT, SERVER_ERROR, AMBIGUOUS, QUOTA, CLOCK_OFFSET, STORAGE_FULL }

    sealed interface Admission {
        /** Closed: proceed. */
        data object Closed : Admission

        /** Open, or a trial is already running elsewhere: answer `451`. */
        data class Blocked(
            val kinds: Set<Kind>,
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

    /** Whether the open kinds may reopen only on Ops evidence. Call under the lock. */
    private fun evidenceRequired(): Boolean =
        Kind.STORAGE_FULL in kinds || (Kind.AMBIGUOUS in kinds && (ambiguityNeedsEvidence || escalated))

    fun admit(): Admission {
        val evidence = evidenceIfNeeded()
        synchronized(this) {
            if (kinds.isEmpty()) return Admission.Closed
            if (trialInFlight || nanoTime() - retryAt < 0) return Admission.Blocked(kinds.toSet())
            // No trial, and no trial consumed, while a full (or possibly full) filesystem has
            // no evidence of recovery FOR THIS trip generation: evidence read before a later
            // trip is void.
            if (evidenceRequired() && !evidence.holdsFor(epoch)) return Admission.Blocked(kinds.toSet())
            trialInFlight = true
            return Admission.Trial(kinds.toSet(), epoch)
        }
    }

    /** Whether a caller would be refused right now. Consumes no trial. */
    fun isBlocked(): Boolean {
        val evidence = evidenceIfNeeded()
        synchronized(this) {
            if (kinds.isEmpty()) return false
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
                epoch.takeIf { evidenceRequired() && !trialInFlight && nanoTime() - retryAt >= 0 }
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
    ) {
        val failedTrial = kinds.isNotEmpty() && trialInFlight
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

    /** The trial ended without an answer (the event stored nothing): the next caller trials instead. */
    @Synchronized
    fun abandonTrial() {
        trialInFlight = false
    }

    /**
     * [trial] succeeded. It closes the breaker only if nothing tripped since
     * it was issued: a quota trial is a whole real event, and an ambiguous
     * outcome or a clock offset reported meanwhile needs its own trial.
     */
    @Synchronized
    fun close(trial: Admission.Trial) {
        if (trial.epoch == epoch) close() else trialInFlight = false
    }

    /** Closes unconditionally (a success no later trip can have overtaken). */
    @Synchronized
    fun close() {
        kinds.clear()
        trialInFlight = false
        backoff = initialBackoff
        escalated = false
    }

    @get:Synchronized
    val isOpen: Boolean get() = kinds.isNotEmpty()

    @get:Synchronized
    val currentBackoff: Duration get() = backoff
}
