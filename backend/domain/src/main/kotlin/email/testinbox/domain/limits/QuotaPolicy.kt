package email.testinbox.domain.limits

/**
 * The resource dimensions a workspace is bounded on (ADR-027). Quotas bound
 * *retained consumption*; rate limits bound *frequency*. The two are reported
 * differently to callers because only one of them is fixed by waiting.
 */
enum class QuotaDimension {
    ACTIVE_INBOXES,
    STORED_BYTES,
    MESSAGES,
    CONCURRENT_WAITS,
}

/**
 * Per-workspace allowances. Deliberately a flat set of generic values rather
 * than a plan/tier abstraction: future plans can map onto these, and nothing
 * here presumes billing exists.
 */
data class QuotaPolicy(
    val maxActiveInboxes: Long,
    val maxStoredBytes: Long,
    val maxMessages: Long,
    val maxConcurrentWaits: Long,
) {
    init {
        require(maxActiveInboxes > 0) { "maxActiveInboxes must be positive, was $maxActiveInboxes" }
        require(maxStoredBytes > 0) { "maxStoredBytes must be positive, was $maxStoredBytes" }
        require(maxMessages > 0) { "maxMessages must be positive, was $maxMessages" }
        require(maxConcurrentWaits > 0) { "maxConcurrentWaits must be positive, was $maxConcurrentWaits" }
    }

    fun limitFor(dimension: QuotaDimension): Long =
        when (dimension) {
            QuotaDimension.ACTIVE_INBOXES -> maxActiveInboxes
            QuotaDimension.STORED_BYTES -> maxStoredBytes
            QuotaDimension.MESSAGES -> maxMessages
            QuotaDimension.CONCURRENT_WAITS -> maxConcurrentWaits
        }

    /**
     * True when consuming [amount] more of [dimension] on top of [current]
     * stays within the allowance. Saturating arithmetic: a hostile or
     * corrupted size can never wrap into apparent free capacity.
     */
    fun admits(
        dimension: QuotaDimension,
        current: Long,
        amount: Long = 1,
    ): Boolean {
        if (current < 0 || amount < 0) return false
        val projected = saturatingAdd(current, amount)
        return projected <= limitFor(dimension)
    }

    private fun saturatingAdd(
        a: Long,
        b: Long,
    ): Long {
        val sum = a + b
        // Overflow iff the operands share a sign that the result does not.
        return if (((a xor sum) and (b xor sum)) < 0) Long.MAX_VALUE else sum
    }
}

/** Why a quota check refused, and against which dimension. */
data class QuotaExceeded(
    val dimension: QuotaDimension,
    val limit: Long,
    val current: Long,
)
