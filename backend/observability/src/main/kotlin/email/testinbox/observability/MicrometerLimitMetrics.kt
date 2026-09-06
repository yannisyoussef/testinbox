package email.testinbox.observability

import email.testinbox.application.port.LimitMetrics
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer implementation of the limit metrics port (ADR-027 §9,
 * `docs/architecture/observability.md`).
 *
 * Every label here is drawn from a **closed enum or a boolean**. Nothing
 * caller-controlled — workspace id, API key, inbox id, email address — is
 * ever a label: those are unbounded in cardinality and an attacker choosing
 * them chooses how much memory the metrics backend spends. Attribution to a
 * specific tenant belongs in the structured logs, which are access-controlled
 * and already carry a correlation id.
 */
class MicrometerLimitMetrics(
    private val registry: MeterRegistry,
) : LimitMetrics {
    private val activeWaitSlots = AtomicLong(0)

    init {
        registry.gauge("testinbox_wait_slots_active", activeWaitSlots) { it.get().toDouble() }
    }

    override fun rateDecision(
        category: RateCategory,
        allowed: Boolean,
    ) {
        registry
            .counter(
                "testinbox_rate_decision_total",
                "category",
                category.name,
                "outcome",
                if (allowed) "allowed" else "rejected",
            ).increment()
    }

    override fun quotaRejected(dimension: QuotaDimension) {
        registry.counter("testinbox_quota_rejected_total", "quota", dimension.name).increment()
    }

    override fun waitSlotRejected() {
        registry.counter("testinbox_wait_slot_rejected_total").increment()
    }

    override fun waitSlotsChanged(delta: Int) {
        activeWaitSlots.addAndGet(delta.toLong())
    }
}
