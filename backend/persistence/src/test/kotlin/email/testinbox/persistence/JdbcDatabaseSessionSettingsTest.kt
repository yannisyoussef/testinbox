package email.testinbox.persistence

import email.testinbox.application.deployment.DatabaseSessionPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * Against a real PostgreSQL, because the whole point is what the server
 * actually renders: the parser is unit-tested on the strings it is expected to
 * see, and this proves those are the strings it gets.
 *
 * One connection per case rather than the pool, so a `SET` made here is the
 * value the next `SHOW` reads — the way an Ops per-role or per-database
 * setting reaches a pooled session.
 */
class JdbcDatabaseSessionSettingsTest : PersistenceIntegrationTest() {
    private fun singleSession(): SingleConnectionDataSource =
        SingleConnectionDataSource(postgres.jdbcUrl, postgres.username, postgres.password, true)

    @Test
    fun `PostgreSQL's default is disabled, and the policy says so`() {
        val session = singleSession()
        try {
            val settings = JdbcDatabaseSessionSettings(JdbcClient.create(session))
            settings.idleInTransactionSessionTimeout() shouldBe "0"
            DatabaseSessionPolicy(settings).status().bounded shouldBe false
        } finally {
            session.destroy()
        }
    }

    @Test
    fun `the recommended production value reads back in the unit the server renders`() {
        val session = singleSession()
        try {
            val jdbc = JdbcClient.create(session)
            jdbc.sql("SET idle_in_transaction_session_timeout = '30s'").update()
            val settings = JdbcDatabaseSessionSettings(jdbc)
            settings.idleInTransactionSessionTimeout() shouldBe "30s"
            val status = DatabaseSessionPolicy(settings).status()
            status.bounded shouldBe true
            status.timeout?.toSeconds() shouldBe 30L
        } finally {
            session.destroy()
        }
    }

    @Test
    fun `a value set in milliseconds is rendered in the largest whole unit and still parsed`() {
        val session = singleSession()
        try {
            val jdbc = JdbcClient.create(session)
            jdbc.sql("SET idle_in_transaction_session_timeout = 90000").update()
            val status = DatabaseSessionPolicy(JdbcDatabaseSessionSettings(jdbc)).status()
            // 90000 ms is not a whole number of minutes, so the server keeps it in seconds.
            status.raw shouldBe "90s"
            status.timeout?.toSeconds() shouldBe 90L
            status.bounded shouldBe true
        } finally {
            session.destroy()
        }
    }
}
