package email.testinbox.persistence

import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageRefusalReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * ADR-035 §13c: one wait evaluation reads messages, attachments, the refusal
 * record and, on demand, the byte figures from ONE snapshot (TI-STORAGE-004
 * §17, §18). The seam between the message read and the refusal read commits
 * rows from another connection; under REPEATABLE READ they are invisible, and
 * a READ COMMITTED mutant proves the test can fail.
 */
class WaitObservationsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var messages: JdbcMessageRepository
    private lateinit var visibility: JdbcStorageVisibility

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        transactionManager = DataSourceTransactionManager(fx.db.dataSource)
        messages = JdbcMessageRepository(fx.db.jdbc)
        visibility = JdbcStorageVisibility(fx.db.jdbc)
    }

    /** Commits on a connection of its own, so the commit is concurrent with the observation's transaction. */
    private fun commitElsewhere(block: (java.sql.Connection) -> Unit) {
        fx.db.dataSource.connection.use { other ->
            other.autoCommit = false
            block(other)
            other.commit()
        }
    }

    private fun refuseElsewhere(
        inbox: UUID,
        ws: UUID,
    ) = commitElsewhere { c ->
        c
            .prepareStatement(
                """
                INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason)
                VALUES (?, ?, 1, now(), 'INBOX_LIMIT')
                ON CONFLICT (inbox_id) DO UPDATE SET refusal_count = inbox_storage.refusal_count + 1,
                    last_refusal_at = EXCLUDED.last_refusal_at, last_refusal_reason = EXCLUDED.last_refusal_reason
                """.trimIndent(),
            ).use {
                it.setObject(1, inbox)
                it.setObject(2, ws)
                it.executeUpdate()
            }
    }

    private fun messageElsewhere(
        inbox: UUID,
        ws: UUID,
        raw: Long = 5,
    ) = commitElsewhere { c ->
        c
            .prepareStatement(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key, raw_size_bytes,
                                     content_fingerprint, parse_status, headers, links)
                VALUES (?, ?, ?, now(), 'local-smtp', 'x@t', 'k', ?, 'fp', 'OK', '[]'::jsonb, '[]'::jsonb)
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, ws)
                it.setObject(3, inbox)
                it.setLong(4, raw)
                it.executeUpdate()
            }
    }

    private fun reservationElsewhere(
        inbox: UUID,
        ws: UUID,
        bytes: Long,
    ) = commitElsewhere { c ->
        c
            .prepareStatement(
                """
                INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                VALUES (?, ?, ?, ARRAY['k'], ?, 'RESERVED', now(), now() + interval '2 minutes', 'seed', ?)
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, ws)
                it.setObject(3, inbox)
                it.setLong(4, bytes)
                it.setObject(5, UUID.randomUUID())
                it.executeUpdate()
            }
    }

    private fun observations(
        between: () -> Unit = {},
        isolation: String? = null,
    ) = object : JdbcWaitObservations(fx.db.jdbc, transactionManager, messages, visibility) {
        override fun betweenReads() = between()

        override fun isolation(): String = isolation ?: super.isolation()
    }

    @Test
    fun `a refusal and a message committed between the message read and the refusal read are invisible to that evaluation`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val seen =
            observations(between = {
                refuseElsewhere(inbox, ws)
                messageElsewhere(inbox, ws)
                reservationElsewhere(inbox, ws, 77)
            }).observe(WorkspaceId(ws), InboxId(inbox)) { observed ->
                Triple(observed.messages.size, observed.refusals, observed.storage())
            }
        seen.first shouldBe 0
        seen.second shouldBe StorageRefusalSnapshot.NONE
        seen.third.inbox.reservedBytes shouldBe 0
        seen.third.workspace.reservedBytes shouldBe 0
        // The rows exist; they were simply committed after the snapshot.
        visibility.refusalsOf(WorkspaceId(ws), InboxId(inbox)).count shouldBe 1
        messages.listVisible(InboxId(inbox)).size shouldBe 1
    }

    @Test
    fun `the detector detects - a READ COMMITTED mutant sees the refusal the messages did not see`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val seen =
            observations(between = { refuseElsewhere(inbox, ws) }, isolation = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED READ ONLY")
                .observe(WorkspaceId(ws), InboxId(inbox)) { observed -> observed.messages.size to observed.refusals.count }
        // Two snapshots: no message, but a refusal. Exactly the inversion the single snapshot forbids.
        seen shouldBe (0 to 1L)
    }

    @Test
    fun `messages and their attachments come from the same snapshot, and are complete`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val id = db.message(ws, inbox, rawBytes = 10, attachments = listOf(3, 4))!!
        val observed =
            observations().observe(WorkspaceId(ws), InboxId(inbox)) { it.messages }
        observed.single().id.value shouldBe id
        observed
            .single()
            .attachments
            .map { it.sizeBytes }
            .sorted() shouldBe listOf(3L, 4L)
    }

    @Test
    fun `the evaluation's transaction is closed when it returns, and is never a caller's`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val o = observations()
        o.observe(WorkspaceId(ws), InboxId(inbox)) { TransactionSynchronizationManager.isActualTransactionActive() } shouldBe true
        TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
        // No idle-in-transaction session is left behind.
        db.jdbc
            .sql("SELECT count(*) FROM pg_stat_activity WHERE state = 'idle in transaction' AND datname = current_database()")
            .query(Long::class.java)
            .single() shouldBe 0
        // Inside another transaction the evaluation refuses to run: it would inherit the wrong isolation and lifetime.
        shouldThrow<IllegalStateException> {
            fx.db.transactions.execute { o.observe(WorkspaceId(ws), InboxId(inbox)) { 1 } }
        }
    }

    @Test
    fun `the refusal record is read through the real §6a write`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val reservations = JdbcStorageReservations(db.jdbc, db.transactions)
        db.transactions.executeWithoutResult { reservations.recordRefusals(mapOf(InboxId(inbox) to StorageRefusalReason.SERVICE_CAPACITY)) }
        val refusals = observations().observe(WorkspaceId(ws), InboxId(inbox)) { it.refusals }
        refusals.count shouldBe 1
        refusals.lastReason shouldBe StorageRefusalReason.SERVICE_CAPACITY
    }
}
