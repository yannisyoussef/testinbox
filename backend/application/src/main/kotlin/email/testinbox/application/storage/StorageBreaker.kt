package email.testinbox.application.storage

import java.time.Duration

/**
 * ADR-035 §8: the per-node storage circuit breaker. It is infrastructure,
 * never capacity. An open breaker answers `451` before the slot and before
 * T1: no reservation, no lock.
 *
 * It opens on any physical failure: an ambiguous upload, the quota `400`, an
 * unreachable storage, or a clock offset above `ε_max`. After a backoff
 * (15 s, doubling to 2 min), ONE caller gets the half-open trial:
 * - [Admission.Probe] for every kind except quota: a zero-byte probe (or, for
 *   a clock offset, a fresh measurement);
 * - [Admission.RealTrial] for quota: a real event. MinIO's lagging quota can
 *   accept a zero-byte probe while real uploads still fail (probes Q2–Q7),
 *   and a quota outcome is definitive, so the trial risks nothing in H.
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
            val kind: Kind,
        ) : Admission

        /** This caller runs the zero-byte probe (or offset check); on success the breaker closes. */
        data class Probe(
            val kind: Kind,
        ) : Admission

        /** This caller's real event is the quota trial. */
        data object RealTrial : Admission
    }

    private var open = false
    private var kind = Kind.UNAVAILABLE
    private var backoff = initialBackoff
    private var retryAt = 0L
    private var trialInFlight = false

    @Synchronized
    fun admit(): Admission {
        if (!open) return Admission.Closed
        if (trialInFlight || nanoTime() - retryAt < 0) return Admission.Blocked(kind)
        trialInFlight = true
        return if (kind == Kind.QUOTA) Admission.RealTrial else Admission.Probe(kind)
    }

    /** A physical failure: open, or re-open after a failed trial with a doubled backoff. */
    @Synchronized
    fun trip(kind: Kind) {
        backoff =
            if (open && trialInFlight) {
                minOf(backoff.multipliedBy(2), maxBackoff)
            } else if (open) {
                backoff
            } else {
                initialBackoff
            }
        open = true
        this.kind = kind
        trialInFlight = false
        retryAt = nanoTime() + backoff.toNanos()
    }

    /** The trial ended without an answer (the event stored nothing): the next caller trials instead. */
    @Synchronized
    fun abandonTrial() {
        trialInFlight = false
    }

    /** The trial succeeded. */
    @Synchronized
    fun close() {
        open = false
        trialInFlight = false
        backoff = initialBackoff
    }

    @get:Synchronized
    val isOpen: Boolean get() = open

    @get:Synchronized
    val currentBackoff: Duration get() = backoff
}
