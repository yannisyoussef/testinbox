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
) {
    init {
        // Default-deny: a category with no configured policy would otherwise be
        // silently unlimited the moment someone adds one.
        val missing = RateCategory.entries.filterNot { it in rates }
        require(missing.isEmpty()) { "no rate policy configured for $missing" }
    }

    fun rateFor(category: RateCategory): RatePolicy = rates.getValue(category)

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
            )
    }
}
