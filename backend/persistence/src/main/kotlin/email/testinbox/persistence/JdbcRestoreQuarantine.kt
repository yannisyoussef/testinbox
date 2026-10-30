package email.testinbox.persistence

import email.testinbox.application.port.RestoreQuarantine
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Reads the V22 restore clearance: quarantined while the one-row table is
 * empty. The API role already reads it (readiness `lifecyclePrivileges`
 * proves the grant); the SMTP role does not and never constructs this.
 */
class JdbcRestoreQuarantine(
    private val jdbc: JdbcClient,
) : RestoreQuarantine {
    override fun active(): Boolean =
        jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM lifecycle_restore_clearance)").query(Boolean::class.javaObjectType).single()
}
