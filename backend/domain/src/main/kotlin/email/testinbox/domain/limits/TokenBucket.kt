package email.testinbox.domain.limits

import java.time.Duration
import java.time.Instant

/**
 * The cost classes a request is charged against (ADR-027). Operations differ
 * in what they actually consume, so they are limited separately rather than
 * against one global requests-per-minute number.
 */
enum class RateCategory {
    /** Creates durable state. */
    INBOX_CREATE,

    /** Holds server resources over time; concurrency is governed separately. */
    WAIT,

    /** Cheap metadata reads. */
    READ,

    /** Bandwidth and storage-read intensive. */
    DOWNLOAD,
}

/**
 * Capacity is the burst allowance; [refillPerSecond] is the sustained rate.
 * Two numbers, because legitimate CI traffic is bursty but bounded.
 */
data class RatePolicy(
    val capacity: Long,
    val refillPerSecond: Double,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
        require(refillPerSecond > 0.0) { "refillPerSecond must be positive, was $refillPerSecond" }
        require(refillPerSecond.isFinite()) { "refillPerSecond must be finite" }
    }

    /** Time for one whole token to accrue — the exact `Retry-After` for an empty bucket. */
    val timeToOneToken: Duration = Duration.ofNanos(Math.ceil(NANOS_PER_SECOND / refillPerSecond).toLong())

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * Pure token-bucket arithmetic (ADR-027). Deliberately framework-free and
 * storage-free: the persistence adapter owns *where* `tokens`/`updatedAt`
 * live and the atomicity of the update; this owns *what* the new state is.
 * Keeping the arithmetic here is what makes boundary behaviour testable
 * without a database or a sleep.
 */
object TokenBucket {
    data class State(
        val tokens: Double,
        val updatedAt: Instant,
    )

    data class Decision(
        val allowed: Boolean,
        val state: State,
        /** Whole tokens left after the decision — what `RateLimit-Remaining` reports. */
        val remaining: Long,
        /** Absent when allowed; otherwise how long until one token exists. */
        val retryAfter: Duration?,
    )

    /**
     * Refills [state] by the time elapsed to [now] and spends one token when
     * available. A clock that moves backwards (NTP correction, a test clock
     * rewound) never fabricates tokens: elapsed time is clamped at zero.
     */
    fun spend(
        state: State,
        policy: RatePolicy,
        now: Instant,
    ): Decision {
        val refilled = refill(state, policy, now)
        if (refilled.tokens >= 1.0) {
            val spent = State(refilled.tokens - 1.0, now)
            return Decision(
                allowed = true,
                state = spent,
                remaining = Math.floor(spent.tokens).toLong(),
                retryAfter = null,
            )
        }
        val missing = 1.0 - refilled.tokens
        val waitNanos = Math.ceil(missing / policy.refillPerSecond * NANOS_PER_SECOND)
        return Decision(
            allowed = false,
            state = refilled,
            remaining = 0,
            // Never advertise "retry immediately" for a refusal the caller would just repeat.
            retryAfter = Duration.ofNanos(waitNanos.toLong()).coerceAtLeast(Duration.ofNanos(1)),
        )
    }

    /** A full bucket for a scope seen for the first time. */
    fun initial(
        policy: RatePolicy,
        now: Instant,
    ): State = State(policy.capacity.toDouble(), now)

    private fun refill(
        state: State,
        policy: RatePolicy,
        now: Instant,
    ): State {
        // A non-finite stored value would make every later comparison false and
        // strand the bucket permanently refusing — a tenant outage that no elapsed
        // time repairs. Treat it as empty and let it refill normally.
        val current = if (state.tokens.isFinite()) state.tokens else 0.0
        val elapsedNanos = Duration.between(state.updatedAt, now).toNanos().coerceAtLeast(0)
        if (elapsedNanos == 0L) return State(current.coerceIn(0.0, policy.capacity.toDouble()), state.updatedAt)
        val gained = elapsedNanos / NANOS_PER_SECOND * policy.refillPerSecond
        // Clamped at capacity, so an idle bucket cannot accumulate an unbounded burst
        // and the arithmetic cannot overflow however long the scope was untouched.
        val tokens = (current + gained).coerceIn(0.0, policy.capacity.toDouble())
        return State(tokens, now)
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
