package email.testinbox.application

import email.testinbox.domain.limits.QuotaPolicy
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy

/**
 * Resolved limit configuration (ADR-027 §9). Defaults are generous enough
 * that local development and the existing suites are unaffected; tests
 * override with very small values so boundaries are exercised in
 * milliseconds.
 */
data class LimitsConfig(
    val enabled: Boolean,
    val quotas: QuotaPolicy,
    val rates: Map<RateCategory, RatePolicy>,
    /**
     * INGEST budget for a single inbox. Deliberately smaller than the
     * workspace-wide INGEST policy: if the two were equal, a flood against one
     * guessed EXACT address would drain the workspace budget and starve every
     * other inbox — which is the whole point of having a per-inbox scope.
     */
    val perInboxIngest: RatePolicy,
) {
    init {
        // Default-deny: a category with no configured policy would otherwise be
        // silently unlimited the moment someone adds one.
        val missing = RateCategory.entries.filterNot { it in rates }
        require(missing.isEmpty()) { "no rate policy configured for $missing" }
        require(perInboxIngest.capacity <= rates.getValue(RateCategory.INGEST).capacity) {
            "per-inbox INGEST capacity must not exceed the workspace-wide one"
        }
    }

    fun rateFor(
        category: RateCategory,
        perInbox: Boolean = false,
    ): RatePolicy = if (perInbox && category == RateCategory.INGEST) perInboxIngest else rates.getValue(category)

    companion object {
        /**
         * Used when `testinbox.limits.enabled=false`. Enforcement code paths
         * still run — so the disabled mode cannot diverge from the enforced one
         * — but nothing is ever refused.
         */
        fun unlimited(): LimitsConfig =
            LimitsConfig(
                enabled = false,
                quotas =
                    QuotaPolicy(
                        maxActiveInboxes = Long.MAX_VALUE,
                        maxStoredBytes = Long.MAX_VALUE,
                        maxConcurrentWaits = Long.MAX_VALUE,
                    ),
                rates =
                    RateCategory.entries.associateWith {
                        RatePolicy(capacity = Long.MAX_VALUE / 2, refillPerSecond = 1e9)
                    },
                perInboxIngest = RatePolicy(capacity = Long.MAX_VALUE / 2, refillPerSecond = 1e9),
            )
    }
}
