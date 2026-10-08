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
 * - any other non-quota kind needs a zero-risk witness probe;
 * - quota needs a real event, after the probes pass. MinIO's lagging quota
 *   can accept a probe while real uploads still fail (probes Q2–Q7), and a
 *   quota outcome is definitive, so the trial risks nothing in H.
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
) {
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
             * Whether the caller's real event is part of the trial: quota and a
             * full filesystem both accept a zero-byte probe, so only a real
             * allocation through the normal reservation path proves recovery.
             */
            val needsRealEvent: Boolean get() = Kind.QUOTA in kinds || Kind.STORAGE_FULL in kinds
        }
    }

    private val kinds = LinkedHashSet<Kind>()
    private var backoff = initialBackoff
    private var retryAt = 0L
    private var trialInFlight = false

    /** Bumped on every trip, so a trial only ever closes the trips it was issued for. */
    private var epoch = 0L

    fun admit(): Admission {
        val evidence = evidenceIfNeeded()
        synchronized(this) {
            if (kinds.isEmpty()) return Admission.Closed
            if (trialInFlight || nanoTime() - retryAt < 0) return Admission.Blocked(kinds.toSet())
            // No trial, and no trial consumed, while a full filesystem has no evidence of recovery.
            if (Kind.STORAGE_FULL in kinds && evidence != true) return Admission.Blocked(kinds.toSet())
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
            return Kind.STORAGE_FULL in kinds && evidence != true
        }
    }

    /**
     * The evidence check, run OUTSIDE the lock (it reads the database), and
     * only when a STORAGE_FULL trial could be due. Null means "not checked":
     * a STORAGE_FULL trip that lands between the two looks counts as no
     * evidence, so the race can only keep the breaker shut.
     */
    private fun evidenceIfNeeded(): Boolean? {
        val due =
            synchronized(this) {
                Kind.STORAGE_FULL in kinds && !trialInFlight && nanoTime() - retryAt >= 0
            }
        if (!due) return null
        return try {
            storageFullGate.evidence()
        } catch (e: Exception) {
            false
        }
    }

    /** A physical failure: open, or re-open after a failed trial with a doubled backoff. */
    @Synchronized
    fun trip(kind: Kind) {
        backoff =
            if (kinds.isNotEmpty() && trialInFlight) {
                minOf(backoff.multipliedBy(2), maxBackoff)
            } else if (kinds.isNotEmpty()) {
                backoff
            } else {
                initialBackoff
            }
        kinds += kind
        epoch++
        trialInFlight = false
        retryAt = nanoTime() + backoff.toNanos()
        // Every STORAGE_FULL trip, a failed trial's included, invalidates every earlier observation.
        if (kind == Kind.STORAGE_FULL) storageFullGate.tripped()
    }

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
    }

    @get:Synchronized
    val isOpen: Boolean get() = kinds.isNotEmpty()

    @get:Synchronized
    val currentBackoff: Duration get() = backoff
}
