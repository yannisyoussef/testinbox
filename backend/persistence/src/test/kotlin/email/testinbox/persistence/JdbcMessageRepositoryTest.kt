package email.testinbox.persistence

import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.postgresql.PGConnection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Connection
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class JdbcMessageRepositoryTest : PersistenceIntegrationTest() {
    @Autowired lateinit var messages: JdbcMessageRepository

    @Autowired lateinit var inboxes: JdbcInboxRepository

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var tx: TransactionRunner

    private fun listenConnection(): Connection {
        val connection =
            java.sql.DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        connection.createStatement().use { it.execute("LISTEN $NOTIFICATION_CHANNEL") }
        return connection
    }

    @Test
    fun `appendVisible persists message with parsed fields, attachments and jsonb round-trip`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        val message =
            Fixtures.message(inbox).let {
                it.copy(
                    parsed =
                        it.parsed!!.copy(
                            headers =
                                listOf(
                                    email.testinbox.domain.message
                                        .EmailHeader("X-Run", "1"),
                                ),
                            links =
                                listOf(
                                    email.testinbox.domain.message
                                        .EmailLink("https://x.test/verify", "Verify"),
                                ),
                        ),
                    attachments =
                        listOf(
                            email.testinbox.domain.message.Attachment(
                                id = email.testinbox.domain.AttachmentId(UUID.randomUUID()),
                                messageId = it.id,
                                fileName = "invoice.pdf",
                                contentType = "application/pdf",
                                sizeBytes = 2,
                                objectKey = "k/attachments/a",
                            ),
                        ),
                )
            }
        messages.appendVisible(message) shouldBe AppendOutcome.Appended
        val loaded = messages.findById(workspaceId, message.id).shouldNotBeNull()
        loaded.parsed!!.subject shouldBe "hello"
        loaded.parsed!!
            .headers
            .single()
            .name shouldBe "X-Run"
        loaded.parsed!!
            .links
            .single()
            .href shouldBe "https://x.test/verify"
        loaded.attachments.single().fileName shouldBe "invoice.pdf"
        loaded.contentFingerprint shouldBe message.contentFingerprint
    }

    @Test
    fun `notification is delivered only after the inserting transaction commits (ADR-020)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        listenConnection().use { listener ->
            val pg = listener.unwrap(PGConnection::class.java)
            tx.required {
                messages.appendVisible(Fixtures.message(inbox))
                // Inside the (joined) transaction: nothing may be delivered yet.
                pg.getNotifications(300).let { it?.size ?: 0 } shouldBe 0
            }
            // After commit the notification arrives, payload = inbox id (wake-up hint only).
            await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted {
                val delivered = pg.getNotifications(200) ?: emptyArray()
                delivered.count {
                    it.name == NOTIFICATION_CHANNEL && it.parameter == inbox.id.value.toString()
                } shouldBe 1
            }
        }
    }

    @Test
    fun `same provider delivery event is a no-op enforced by the partial unique index (ADR-019)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        val first = Fixtures.message(inbox, providerMessageId = "ses-evt-1")
        messages.appendVisible(first) shouldBe AppendOutcome.Appended
        val replay = Fixtures.message(inbox, providerMessageId = "ses-evt-1")
        messages.appendVisible(replay) shouldBe AppendOutcome.DuplicateProviderEvent
        messages.listVisible(inbox.id).size shouldBe 1
    }

    @Test
    fun `byte-identical messages without provider event ids are both persisted (ADR-019)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        val fingerprint = "same-content"
        messages.appendVisible(Fixtures.message(inbox, fingerprint = fingerprint)) shouldBe
            AppendOutcome.Appended
        messages.appendVisible(Fixtures.message(inbox, fingerprint = fingerprint)) shouldBe
            AppendOutcome.Appended
        messages.listVisible(inbox.id).size shouldBe 2
        messages.findEarliestIdByFingerprint(inbox.id, fingerprint).shouldNotBeNull()
    }

    @Test
    fun `workspace scoping - a message is invisible to another workspace (threat model)`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val (otherWorkspace, _) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        val message = Fixtures.message(inbox)
        messages.appendVisible(message)
        messages.findById(otherWorkspace, message.id).shouldBeNull()
        messages.findById(workspaceId, message.id).shouldNotBeNull()
        messages.listPage(otherWorkspace, inbox.id, null, 10).shouldBeEmpty()
    }

    @Test
    fun `cursor pagination is stable in receipt order`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId)
        inboxes.insert(inbox)
        val base = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val stored =
            (0L until 5L).map { i ->
                Fixtures.message(inbox, receivedAt = base.plusMillis(i)).also { messages.appendVisible(it) }
            }
        val firstPage = messages.listPage(workspaceId, inbox.id, null, 2)
        firstPage.map { it.id } shouldBe stored.take(2).map { it.id }
        val cursor = MessageCursor(firstPage.last().receivedAt, firstPage.last().id)
        val secondPage = messages.listPage(workspaceId, inbox.id, cursor, 10)
        secondPage.map { it.id } shouldBe stored.drop(2).map { it.id }
    }
}
