package email.testinbox.ingestion.config

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Account lifecycle (V22): recipient resolution and every T1 read the
 * eligibility view. Without its grant every event would get a uniform `451`
 * while the node looked healthy; instead the node is NOT READY, with a closed
 * reason, and Ops installs the grant (roles.reference.sql).
 */
@Component("lifecyclePrivileges")
class IngestionLifecyclePrivilegesHealthIndicator(
    jdbc: org.springframework.jdbc.core.simple.JdbcClient,
) : HealthIndicator {
    private val probe = email.testinbox.persistence.JdbcLifecyclePrivilegeProbe(jdbc, "ingestion")

    override fun health(): Health =
        probe.ingestion()?.let { Health.outOfService().withDetail("reason", it.name).build() } ?: Health.up().build()
}
