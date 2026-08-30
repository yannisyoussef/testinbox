package email.testinbox.application

import email.testinbox.domain.limits.QuotaPolicy
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy

/**
 * Configuration binding for `testinbox.limits.*` (ADR-027 §9).
 *
 * Enforcement defaults to ON. A deployment that turns it off logs a startup
 * warning: a silently disabled limiter is indistinguishable from a working
 * one, which is the failure mode that makes a limit worthless.
 */
data class LimitsProperties(
    val enabled: Boolean = true,
    val maxActiveInboxes: Long = 200,
    val maxStoredBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxConcurrentWaits: Long = 50,
    val inboxCreate: RateProperties = RateProperties(capacity = 60, refillPerSecond = 1.0),
    val wait: RateProperties = RateProperties(capacity = 120, refillPerSecond = 4.0),
    val download: RateProperties = RateProperties(capacity = 60, refillPerSecond = 2.0),
    val ingest: RateProperties = RateProperties(capacity = 300, refillPerSecond = 10.0),
) {
    data class RateProperties(
        val capacity: Long,
        val refillPerSecond: Double,
    ) {
        fun toPolicy(): RatePolicy = RatePolicy(capacity, refillPerSecond)
    }

    fun toConfig(): LimitsConfig =
        if (!enabled) {
            LimitsConfig.unlimited()
        } else {
            LimitsConfig(
                enabled = true,
                quotas =
                    QuotaPolicy(
                        maxActiveInboxes = maxActiveInboxes,
                        maxStoredBytes = maxStoredBytes,
                        maxConcurrentWaits = maxConcurrentWaits,
                    ),
                rates =
                    mapOf(
                        RateCategory.INBOX_CREATE to inboxCreate.toPolicy(),
                        RateCategory.WAIT to wait.toPolicy(),
                        RateCategory.DOWNLOAD to download.toPolicy(),
                        RateCategory.INGEST to ingest.toPolicy(),
                    ),
            )
        }
}
