package email.testinbox.application

import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LimitsConfigTest {
    @Test
    fun `every rate category must have a policy, so a new one cannot be silently unlimited`() {
        val incomplete =
            runCatching {
                LimitsConfig(
                    enabled = true,
                    quotas = LimitsProperties().toConfig().quotas,
                    rates = mapOf(RateCategory.INBOX_CREATE to LimitsProperties().inboxCreate.toPolicy()),
                    perInboxIngest = LimitsProperties().ingestPerInbox.toPolicy(),
                )
            }
        incomplete.isFailure shouldBe true
        incomplete.exceptionOrNull()!!.message!!.contains("no rate policy configured") shouldBe true
    }

    @Test
    fun `the per-inbox inbound budget may not exceed the workspace-wide one`() {
        // Otherwise the per-inbox scope buys nothing: one inbox could still
        // drain the whole workspace's inbound allowance.
        val inverted =
            LimitsProperties(
                ingest = LimitsProperties.RateProperties(capacity = 10, refillPerSecond = 1.0),
                ingestPerInbox = LimitsProperties.RateProperties(capacity = 100, refillPerSecond = 1.0),
            )
        runCatching { inverted.toConfig() }.isFailure shouldBe true
    }

    @Test
    fun `the per-inbox budget is used only for INGEST with an inbox scope`() {
        val config = LimitsProperties().toConfig()
        config.rateFor(RateCategory.INGEST, perInbox = true) shouldBe config.perInboxIngest
        config.rateFor(RateCategory.INGEST, perInbox = false) shouldBe
            config.rates.getValue(RateCategory.INGEST)
        // A per-inbox flag on another category is meaningless and must not
        // silently swap in the inbound policy.
        config.rateFor(RateCategory.DOWNLOAD, perInbox = true) shouldBe
            config.rates.getValue(RateCategory.DOWNLOAD)
    }

    @Test
    fun `disabling enforcement refuses nothing but keeps the same code paths`() {
        val disabled = LimitsProperties(enabled = false).toConfig()
        disabled.enabled shouldBe false
        // Every category still resolves — the enforcement path runs unchanged,
        // so a disabled deployment cannot diverge from an enforcing one.
        RateCategory.entries.forEach { (disabled.rateFor(it).capacity > 0) shouldBe true }
        QuotaDimension.entries.forEach {
            disabled.quotas.admits(it, current = Long.MAX_VALUE / 4) shouldBe true
        }
    }

    @Test
    fun `defaults are generous enough that ordinary local development never trips them`() {
        val defaults = LimitsProperties().toConfig()
        defaults.enabled shouldBe true
        (defaults.quotas.maxActiveInboxes >= 100) shouldBe true
        (defaults.rateFor(RateCategory.INBOX_CREATE).capacity >= 10) shouldBe true
        (defaults.perInboxIngest.capacity <= defaults.rateFor(RateCategory.INGEST).capacity) shouldBe true
    }
}
