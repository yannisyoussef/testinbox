package email.testinbox.api.ops

import email.testinbox.persistence.JdbcRestoreQuarantine
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * `testinbox_restore_quarantined`: 1 while the database carries no restore
 * clearance (V22), 0 otherwise, NaN when it cannot be read. No labels.
 * Deliberately NOT a readiness failure: a quarantined API must stay up so
 * the operator can reconcile and re-key (LIFECYCLE.md, restore procedure).
 * Ops alerts on it outside a declared restore window.
 */
@Component
class RestoreQuarantineGauge(
    registry: MeterRegistry,
    jdbc: JdbcClient,
) {
    private val quarantine = JdbcRestoreQuarantine(jdbc)

    init {
        Gauge
            .builder(NAME, this) { it.sample() }
            .description("1 while the database is restore-quarantined (no lifecycle_restore_clearance row)")
            .register(registry)
    }

    internal fun sample(): Double = runCatching { if (quarantine.active()) 1.0 else 0.0 }.getOrDefault(Double.NaN)

    companion object {
        const val NAME = "testinbox_restore_quarantined"
    }
}
