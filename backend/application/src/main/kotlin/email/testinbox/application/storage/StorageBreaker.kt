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
) {
    enum class Kind { UNAVAILABLE, TIMEOUT, SERVER_ERROR, AMBIGUOUS, QUOTA, CLOCK_OFFSET }

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
        ) : Admission {
            /** Whether the caller's real event is part of the trial (quota). */
            val needsRealEvent: Boolean get() = Kind.QUOTA in kinds
        }
    }

    private val kinds = LinkedHashSet<Kind>()
    private var backoff = initialBackoff
    private var retryAt = 0L
    private var trialInFlight = false

    @Synchronized
    fun admit(): Admission {
        if (kinds.isEmpty()) return Admission.Closed
        if (trialInFlight || nanoTime() - retryAt < 0) return Admission.Blocked(kinds.toSet())
        trialInFlight = true
        return Admission.Trial(kinds.toSet())
    }

    /** Whether a caller would be refused right now. Consumes no trial. */
    @Synchronized
    fun isBlocked(): Boolean = kinds.isNotEmpty() && (trialInFlight || nanoTime() - retryAt < 0)

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
        trialInFlight = false
        retryAt = nanoTime() + backoff.toNanos()
    }

    /** The trial ended without an answer (the event stored nothing): the next caller trials instead. */
    @Synchronized
    fun abandonTrial() {
        trialInFlight = false
    }

    /** The trial succeeded for every open kind. */
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
