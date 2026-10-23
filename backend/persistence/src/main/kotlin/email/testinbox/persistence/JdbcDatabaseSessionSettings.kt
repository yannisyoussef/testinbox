package email.testinbox.persistence

import email.testinbox.application.deployment.DatabaseSessionSettings
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * `SHOW` answers for the connection that asks, with no privilege required, so
 * what comes back is the effective value for a pooled application session —
 * whether Ops set it server-wide, per database or per role.
 */
class JdbcDatabaseSessionSettings(
    private val jdbc: JdbcClient,
) : DatabaseSessionSettings {
    override fun idleInTransactionSessionTimeout(): String =
        jdbc
            .sql("SHOW idle_in_transaction_session_timeout")
            .query(String::class.java)
            .single()
}
