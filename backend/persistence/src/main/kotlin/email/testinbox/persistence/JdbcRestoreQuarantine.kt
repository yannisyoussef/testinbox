package email.testinbox.persistence

import email.testinbox.application.port.RestoreQuarantine
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Reads `lifecycle_restore_clearance` (V22): quarantined while it has no row.
 * Only the API calls this (API-key authentication); the SMTP role has no grant
 * on the table and never needs one.
 */
@Repository
class JdbcRestoreQuarantine(
    private val jdbc: JdbcClient,
) : RestoreQuarantine {
    override fun quarantined(): Boolean =
        jdbc.sql("SELECT NOT EXISTS (SELECT 1 FROM lifecycle_restore_clearance)").query(Boolean::class.javaObjectType).single()
}
