package email.testinbox.domain.limits

/**
 * The resource dimensions a workspace is bounded on (ADR-027). Quotas bound
 * *retained consumption*; rate limits bound *frequency*. The two are reported
 * differently to callers because only one of them is fixed by waiting.
 */
enum class QuotaDimension {
    ACTIVE_INBOXES,

    /**
     * Raw MIME plus extracted attachment objects. Attachment bytes are
     * counted twice on purpose — once inside `raw.eml` and once as the
     * extracted object — because under the ADR-005 per-message key layout
     * both objects physically exist.
     */
    STORED_BYTES,
    STORED_FOOTPRINT,
    CONCURRENT_WAITS,

    /** Participant workspaces only: managed keys neither revoked nor expired ([ParticipantKeyInventory]). */
    API_KEYS_USABLE,

    /** Participant workspaces only: every managed key row ever minted, retained as audit history. */
    API_KEYS_RETAINED,
    ;

    companion object {
        /** Dimensions bounded by a fixed participant allocation, not by the configurable [QuotaPolicy]. */
        val FIXED_BOUNDS: Set<QuotaDimension> = setOf(STORED_FOOTPRINT, API_KEYS_USABLE, API_KEYS_RETAINED)
    }
}

/**
 * Per-workspace allowances. Deliberately a flat set of generic values rather
 * than a plan/tier abstraction: future plans can map onto these, and nothing
 * here presumes billing exists.
 */
data class QuotaPolicy(
    val maxActiveInboxes: Long,
    val maxStoredBytes: Long,
    val maxConcurrentWaits: Long,
) {
    init {
        require(maxActiveInboxes > 0) { "maxActiveInboxes must be positive, was $maxActiveInboxes" }
        require(maxStoredBytes > 0) { "maxStoredBytes must be positive, was $maxStoredBytes" }
        require(maxConcurrentWaits > 0) { "maxConcurrentWaits must be positive, was $maxConcurrentWaits" }
    }

    fun limitFor(dimension: QuotaDimension): Long =
        when (dimension) {
            QuotaDimension.ACTIVE_INBOXES -> maxActiveInboxes
            QuotaDimension.STORED_BYTES -> maxStoredBytes
            QuotaDimension.CONCURRENT_WAITS -> maxConcurrentWaits
            QuotaDimension.STORED_FOOTPRINT -> error("footprint limits require a workspace storage share")
            QuotaDimension.API_KEYS_USABLE, QuotaDimension.API_KEYS_RETAINED -> error("key inventory is a fixed participant bound")
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

/**
 * The bounded-beta key inventory of a participant workspace
 * (ACCOUNT-LIFECYCLE.md "Participant key inventory"): at most [MAX_USABLE]
 * usable managed keys, and [MAX_RETAINED] managed key rows in all, covering
 * keys minted by a human owner and by machine keys alike. A revocation or an
 * expiry frees usable capacity, never retained audit history. Owner and
 * monitoring (legacy) workspaces are not bounded by it.
 */
object ParticipantKeyInventory {
    const val MAX_USABLE: Long = 20
    const val MAX_RETAINED: Long = 1000

    /** Null when one more key fits; otherwise the narrowest exhausted dimension. */
    fun refusal(
        usable: Long,
        retained: Long,
    ): QuotaExceeded? =
        when {
            usable >= MAX_USABLE -> QuotaExceeded(QuotaDimension.API_KEYS_USABLE, MAX_USABLE, usable)
            retained >= MAX_RETAINED -> QuotaExceeded(QuotaDimension.API_KEYS_RETAINED, MAX_RETAINED, retained)
            else -> null
        }
}
