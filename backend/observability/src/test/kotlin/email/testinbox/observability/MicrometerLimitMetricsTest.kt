package email.testinbox.observability

import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

class MicrometerLimitMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = MicrometerLimitMetrics(registry)

    @Test
    fun `rate decisions are counted per category and outcome`() {
        metrics.rateDecision(RateCategory.INBOX_CREATE, allowed = true)
        metrics.rateDecision(RateCategory.INBOX_CREATE, allowed = false)
        metrics.rateDecision(RateCategory.INBOX_CREATE, allowed = false)

        registry
            .counter("testinbox_rate_decision_total", "category", "INBOX_CREATE", "outcome", "allowed")
            .count() shouldBe 1.0
        registry
            .counter("testinbox_rate_decision_total", "category", "INBOX_CREATE", "outcome", "rejected")
            .count() shouldBe 2.0
    }

    @Test
    fun `the inbound refusal is counted — the only signal that a live inbox is losing mail`() {
        // ADR-027 §1 keeps the SMTP reply uniform, so nothing tells the sender
        // and nothing tells the tenant. This counter is the whole signal.
        metrics.rateDecision(RateCategory.INGEST, allowed = false)
        registry
            .counter("testinbox_rate_decision_total", "category", "INGEST", "outcome", "rejected")
            .count() shouldBe 1.0
    }

    @Test
    fun `quota rejections are counted per dimension`() {
        metrics.quotaRejected(QuotaDimension.ACTIVE_INBOXES)
        metrics.quotaRejected(QuotaDimension.STORED_BYTES)
        registry.counter("testinbox_quota_rejected_total", "quota", "ACTIVE_INBOXES").count() shouldBe 1.0
        registry.counter("testinbox_quota_rejected_total", "quota", "STORED_BYTES").count() shouldBe 1.0
    }

    @Test
    fun `the active wait gauge returns to zero after paired acquire and release`() {
        metrics.waitSlotsChanged(1)
        metrics.waitSlotsChanged(1)
        gauge() shouldBe 2.0
        metrics.waitSlotsChanged(-1)
        metrics.waitSlotsChanged(-1)
        // A gauge that only ever climbed would make a leak look like load.
        gauge() shouldBe 0.0
    }

    @Test
    fun `every label value is drawn from a closed enum, never from caller-controlled data`() {
        RateCategory.entries.forEach { metrics.rateDecision(it, allowed = false) }
        QuotaDimension.entries.forEach { metrics.quotaRejected(it) }
        metrics.waitSlotRejected()

        val categoryNames = RateCategory.entries.map { it.name }.toSet()
        val quotaNames = QuotaDimension.entries.map { it.name }.toSet()
        registry.meters.forEach { meter ->
            meter.id.tags.forEach { tag ->
                val allowed =
                    when (tag.key) {
                        "category" -> tag.value in categoryNames
                        "quota" -> tag.value in quotaNames
                        "outcome" -> tag.value in setOf("allowed", "rejected")
                        else -> false
                    }
                // An unbounded label would let a caller choose how much memory
                // the metrics backend spends — workspace ids, inbox ids and
                // addresses all belong in the access-controlled logs instead.
                allowed shouldBe true
            }
        }
        // Cardinality is therefore bounded by the enums, not by traffic.
        (registry.meters.size <= categoryNames.size * 2 + quotaNames.size + 2) shouldBe true
    }

    private fun gauge(): Double = registry.find("testinbox_wait_slots_active").gauge()!!.value()
}
