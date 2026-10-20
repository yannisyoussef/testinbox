package email.testinbox.persistence

import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Instant

/**
 * Issue #87: the EXPIRED and DELETED transitions notify the inbox's channel
 * — the same channel and payload a message commit uses — in the SAME
 * transaction (ADR-020), so a parked waiter re-evaluates promptly and sees
 * the new state. Proven against a real LISTEN session: a notification is
 * delivered only once the transition's transaction commits, and never for a
 * transition that did not happen.
 */
class InboxTransitionNotifyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private fun listening(
        db: LedgerTestDatabase,
        block: (Connection, org.postgresql.PGConnection) -> Unit,
    ) {
        db.dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("LISTEN $NOTIFICATION_CHANNEL") }
            block(connection, connection.unwrap(org.postgresql.PGConnection::class.java))
        }
    }

    private fun payloads(pg: org.postgresql.PGConnection): List<String> = (pg.getNotifications(1_000) ?: emptyArray()).map { it.parameter }

    @Test
    fun `EXPIRING to EXPIRED notifies the inbox's channel, at commit`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.jdbc
            .sql("UPDATE inbox SET state = 'EXPIRING', grace_until = now() WHERE id = ?")
            .param(inbox)
            .update()
        val repo = JdbcInboxRepository(db.jdbc)
        val tx = TransactionTemplate(DataSourceTransactionManager(db.dataSource))

        listening(db) { _, pg ->
            payloads(pg).shouldBeEmpty()
            tx.execute {
                repo.transitionToExpired(InboxId(inbox)) shouldBe true
                // Inside the transaction nothing has been delivered yet: notify is atomic with the commit.
                payloads(pg).shouldBeEmpty()
            }
            payloads(pg) shouldBe listOf(inbox.toString())
            // Already EXPIRED: no transition, no notification.
            tx.execute { repo.transitionToExpired(InboxId(inbox)) shouldBe false }
            payloads(pg).shouldBeEmpty()
        }
    }

    @Test
    fun `marking an inbox DELETED notifies the inbox's channel, at commit`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val repo = JdbcInboxRepository(db.jdbc)
        val tx = TransactionTemplate(DataSourceTransactionManager(db.dataSource))

        listening(db) { _, pg ->
            tx.execute {
                checkNotNull(repo.markDeleted(WorkspaceId(ws), InboxId(inbox), Instant.now()))
                payloads(pg).shouldBeEmpty()
            }
            payloads(pg) shouldBe listOf(inbox.toString())
            // The wrong workspace deletes nothing and notifies nothing.
            tx.execute { repo.markDeleted(WorkspaceId(java.util.UUID.randomUUID()), InboxId(inbox), Instant.now()) shouldBe null }
            payloads(pg).shouldBeEmpty()
        }
    }

    @Test
    fun `a rolled-back transition notifies nobody`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.jdbc
            .sql("UPDATE inbox SET state = 'EXPIRING', grace_until = now() WHERE id = ?")
            .param(inbox)
            .update()
        val repo = JdbcInboxRepository(db.jdbc)
        val tx = TransactionTemplate(DataSourceTransactionManager(db.dataSource))

        listening(db) { _, pg ->
            runCatching {
                tx.execute {
                    repo.transitionToExpired(InboxId(inbox)) shouldBe true
                    error("roll it back")
                }
            }
            payloads(pg).shouldBeEmpty()
            db.jdbc
                .sql("SELECT state FROM inbox WHERE id = ?")
                .param(inbox)
                .query(String::class.java)
                .single() shouldBe "EXPIRING"
        }
    }
}
