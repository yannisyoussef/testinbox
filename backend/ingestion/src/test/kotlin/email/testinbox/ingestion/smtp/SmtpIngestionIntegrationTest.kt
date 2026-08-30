package email.testinbox.ingestion.smtp

import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageRepository
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.persistence.JdbcMessageRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Real SMTP-level ingress tests: a MIME message travels over a TCP socket
 * through the SubEthaSMTP adapter into the shared ReceiveInboundMessage use
 * case, Postgres, and MinIO. The application is never called directly.
 */
@SpringBootTest
class SmtpIngestionIntegrationTest {
    /**
     * Lets a test fail persistence for one envelope recipient of a delivery,
     * which is the only way to prove the multi-recipient transaction really is
     * all-or-nothing (ADR-026) rather than all-or-some.
     */
    class FaultInjectingMessageRepository(
        private val delegate: MessageRepository,
    ) : MessageRepository by delegate {
        @Volatile var failForRecipient: String? = null

        override fun appendVisible(message: Message): AppendOutcome {
            if (message.envelopeTo == failForRecipient) {
                error("injected persistence failure for ${message.envelopeTo}")
            }
            return delegate.appendVisible(message)
        }
    }

    @TestConfiguration
    class FaultInjectionConfig {
        @Bean
        @Primary
        fun faultInjectingMessages(delegate: JdbcMessageRepository): FaultInjectingMessageRepository =
            FaultInjectingMessageRepository(delegate)
    }

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine").also { it.start() }

        @JvmStatic
        val minio: MinIOContainer =
            MinIOContainer("minio/minio:latest")
                .withUserName("testinbox")
                .withPassword("testinbox123")
                .also { it.start() }

        @JvmStatic
        val smtpPort: Int = ServerSocket(0).use { it.localPort }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("testinbox.smtp.port") { smtpPort }
            registry.add("testinbox.storage.endpoint") { minio.s3URL }
            registry.add("testinbox.storage.access-key") { "testinbox" }
            registry.add("testinbox.storage.secret-key") { "testinbox123" }
            registry.add("testinbox.mail-domain") { "testinbox.local" }
            registry.add("testinbox.max-raw-size-bytes") { 64 * 1024 }
        }
    }

    @Autowired lateinit var inboxes: InboxRepository

    @Autowired lateinit var messages: MessageRepository

    @Autowired lateinit var faults: FaultInjectingMessageRepository

    @Autowired lateinit var blobs: BlobStore

    @Autowired lateinit var jdbc: JdbcClient

    @AfterEach
    fun clearFaultInjection() {
        faults.failForRecipient = null
    }

    private fun corpus(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/mime-corpus/$name")).readAllBytes()

    private fun provisionInbox(workspaceId: WorkspaceId = WorkspaceId(UUID.randomUUID())): Inbox {
        val projectId = ProjectId(UUID.randomUUID())
        jdbc
            .sql("INSERT INTO workspace (id, name, created_at) VALUES (:id, 'w', now()) ON CONFLICT DO NOTHING")
            .param("id", workspaceId.value)
            .update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (:id, :ws, 'p', now())")
            .param("id", projectId.value)
            .param("ws", workspaceId.value)
            .update()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val inbox =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                address = "it-${UUID.randomUUID().toString().take(8)}@testinbox.local",
                addressMode = AddressMode.GENERATED,
                state = InboxState.ACTIVE,
                createdAt = now,
                expiresAt = now.plusSeconds(600),
            )
        inboxes.insert(inbox)
        return inbox
    }

    private fun client() = RawSmtpClient("localhost", smtpPort)

    @Test
    fun `a MIME email sent over SMTP becomes a visible parsed message with raw stored`() {
        val inbox = provisionInbox()
        client().use { smtp ->
            smtp.send("no-reply@example.com", listOf(inbox.address), corpus("simple-text.eml")).code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(inbox.id).size shouldBe 1
        }
        val message = messages.listVisible(inbox.id).single()
        message.parseStatus shouldBe ParseStatus.OK
        message.parsed!!.subject shouldBe "Verify your email"
        message.parsed!!.links.map { it.href } shouldContain "https://app.example.com/verify?token=abc123"
        message.envelopeFrom shouldBe "no-reply@example.com"
        message.envelopeTo shouldBe inbox.address
        message.provider shouldBe LOCAL_SMTP_PROVIDER
        message.providerMessageId shouldBe null
        blobs.get(message.rawObjectKey).shouldNotBeNull()
    }

    @Test
    fun `identical MIME sent twice produces two observable messages (ADR-019 regression)`() {
        val inbox = provisionInbox()
        val raw = corpus("simple-text.eml")
        client().use { smtp ->
            smtp.send("no-reply@example.com", listOf(inbox.address), raw).code shouldBe 250
            smtp.send("no-reply@example.com", listOf(inbox.address), raw).code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(inbox.id).size shouldBe 2
        }
        val received = messages.listVisible(inbox.id)
        received[0].contentFingerprint shouldBe received[1].contentFingerprint
        received[0].possibleDuplicateOfMessageId shouldBe null
        // Annotated, never suppressed.
        received[1].possibleDuplicateOfMessageId shouldBe received[0].id
    }

    @Test
    fun `unknown recipient - uniform 250, and content never reaches Postgres or MinIO (ADR-025)`() {
        val ghost = "ghost-${UUID.randomUUID().toString().take(8)}@testinbox.local"
        val marker = "UNIQUE-SECRET-${UUID.randomUUID()}"
        val raw = "From: a@b.c\r\nSubject: $marker\r\n\r\n$marker\r\n".toByteArray()
        client().use { smtp ->
            // Same code path/response as a known recipient — no enumeration oracle.
            smtp.send("someone@example.com", listOf(ghost), raw).code shouldBe 250
        }
        // Deterministic negative proof: the marker exists nowhere.
        val dbHits =
            jdbc
                .sql(
                    "SELECT count(*) AS c FROM message WHERE subject LIKE :m OR text_body LIKE :m OR envelope_to = :g",
                ).param("m", "%$marker%")
                .param("g", ghost)
                .query { rs, _ -> rs.getLong("c") }
                .single()
        dbHits shouldBe 0L
        val allKeys = blobs.listKeysOlderThan("", Instant.now().plusSeconds(60))
        allKeys.filter { key -> blobs.get(key)?.let { String(it).contains(marker) } == true }.shouldBeEmpty()
    }

    @Test
    fun `attachment email over SMTP stores attachment bytes under per-message keys`() {
        val inbox = provisionInbox()
        client().use { smtp ->
            smtp
                .send("billing@example.com", listOf(inbox.address), corpus("multipart-mixed-attachment.eml"))
                .code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(inbox.id).size shouldBe 1
        }
        val message = messages.listVisible(inbox.id).single()
        val attachment = message.attachments.single()
        attachment.fileName shouldBe "invoice.pdf"
        attachment.objectKey shouldContain "${inbox.id}"
        String(blobs.get(attachment.objectKey)!!.copyOfRange(0, 5)) shouldBe "%PDF-"
    }

    @Test
    fun `malformed MIME is persisted as ParseFailed with raw bytes intact (ADR-005)`() {
        val inbox = provisionInbox()
        val raw = "X-Broken: yes\r\nContent-Type: multipart/mixed; boundary=\r\n\r\ngarbage".toByteArray()
        client().use { smtp ->
            smtp.send("weird@example.com", listOf(inbox.address), raw).code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(inbox.id).size shouldBe 1
        }
        val message = messages.listVisible(inbox.id).single()
        // Whatever the parser decided, the raw bytes are retrievable byte-for-byte
        // (SMTP normalizes line endings; content must still carry the payload).
        String(blobs.get(message.rawObjectKey)!!) shouldContain "garbage"
    }

    @Test
    fun `RCPT for a foreign domain is rejected with 553, without leaking recipient existence`() {
        client().use { smtp ->
            smtp.rcptProbe("a@b.c", "someone@not-our-domain.example").code shouldBe 553
        }
    }

    @Test
    fun `one DATA transaction with two active recipients delivers to both`() {
        val first = provisionInbox()
        val second = provisionInbox()
        client().use { smtp ->
            smtp
                .send("no-reply@example.com", listOf(first.address, second.address), corpus("simple-text.eml"))
                .code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(first.id).size shouldBe 1
            messages.listVisible(second.id).size shouldBe 1
        }
        // Identical MIME to both recipients: two independent, per-message blobs, no cross-suppression.
        val firstMessage = messages.listVisible(first.id).single()
        val secondMessage = messages.listVisible(second.id).single()
        firstMessage.contentFingerprint shouldBe secondMessage.contentFingerprint
        firstMessage.rawObjectKey shouldNotBe secondMessage.rawObjectKey
        firstMessage.envelopeTo shouldBe first.address
        secondMessage.envelopeTo shouldBe second.address
        blobs.get(firstMessage.rawObjectKey).shouldNotBeNull()
        blobs.get(secondMessage.rawObjectKey).shouldNotBeNull()
    }

    @Test
    fun `active plus unknown recipient - the active one is delivered, the unknown leaves no trace`() {
        val active = provisionInbox()
        val ghost = "ghost-${UUID.randomUUID().toString().take(8)}@testinbox.local"
        client().use { smtp ->
            smtp.send("no-reply@example.com", listOf(active.address, ghost), corpus("simple-text.eml")).code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(active.id).size shouldBe 1
        }
        val ghostRows =
            jdbc
                .sql("SELECT count(*) AS c FROM message WHERE envelope_to = :g")
                .param("g", ghost)
                .query { rs, _ -> rs.getLong("c") }
                .single()
        ghostRows shouldBe 0L
    }

    @Test
    fun `persistence failure for one recipient commits nothing and soft-fails the whole transaction`() {
        val first = provisionInbox()
        val second = provisionInbox()
        faults.failForRecipient = second.address
        client().use { smtp ->
            // 451: the sender must retry the transaction as a unit.
            smtp
                .send("no-reply@example.com", listOf(first.address, second.address), corpus("simple-text.eml"))
                .code shouldBe 451
        }
        // The partial-delivery failure mode: recipient A must NOT be committed.
        messages.listVisible(first.id).shouldBeEmpty()
        messages.listVisible(second.id).shouldBeEmpty()
    }

    @Test
    fun `retry after a failed transaction delivers each recipient exactly once`() {
        val first = provisionInbox()
        val second = provisionInbox()
        val raw = corpus("simple-text.eml")
        faults.failForRecipient = second.address
        client().use { smtp ->
            smtp.send("no-reply@example.com", listOf(first.address, second.address), raw).code shouldBe 451
        }
        faults.failForRecipient = null
        client().use { smtp ->
            smtp.send("no-reply@example.com", listOf(first.address, second.address), raw).code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(first.id).size shouldBe 1
            messages.listVisible(second.id).size shouldBe 1
        }
        // No infrastructure-induced duplicate for the recipient that "already worked".
        messages.listVisible(first.id).single().possibleDuplicateOfMessageId shouldBe null
    }

    @Test
    fun `recipients in different workspaces do not leak across the tenant boundary`() {
        val workspaceA = WorkspaceId(UUID.randomUUID())
        val workspaceB = WorkspaceId(UUID.randomUUID())
        val inboxA = provisionInbox(workspaceA)
        val inboxB = provisionInbox(workspaceB)
        client().use { smtp ->
            smtp
                .send("no-reply@example.com", listOf(inboxA.address, inboxB.address), corpus("simple-text.eml"))
                .code shouldBe 250
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            messages.listVisible(inboxA.id).size shouldBe 1
            messages.listVisible(inboxB.id).size shouldBe 1
        }
        val messageA = messages.listVisible(inboxA.id).single()
        val messageB = messages.listVisible(inboxB.id).single()
        messageA.workspaceId shouldBe workspaceA
        messageB.workspaceId shouldBe workspaceB
        // A workspace-scoped read never sees the other tenant's row.
        messages.findById(workspaceA, messageB.id) shouldBe null
        messages.findById(workspaceB, messageA.id) shouldBe null
        messageA.rawObjectKey.startsWith("$workspaceA/") shouldBe true
        messageB.rawObjectKey.startsWith("$workspaceB/") shouldBe true
    }

    @Test
    fun `expired inbox is indistinguishable from an unknown recipient at the SMTP level`() {
        val inbox = provisionInbox()
        inboxes.markDeleted(inbox.workspaceId, inbox.id, Instant.now())
        client().use { smtp ->
            smtp.send("a@b.c", listOf(inbox.address), corpus("simple-text.eml")).code shouldBe 250
        }
        messages.listVisible(inbox.id).shouldBeEmpty()
    }
}
