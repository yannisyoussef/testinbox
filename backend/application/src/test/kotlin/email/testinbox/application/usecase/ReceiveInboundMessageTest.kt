package email.testinbox.application.usecase

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.MutableClock
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.MimeAttachment
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.ParsedMime
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.ParseStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReceiveInboundMessageTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private lateinit var inboxes: InMemoryInboxRepository
    private lateinit var messages: InMemoryMessageRepository
    private lateinit var blobs: InMemoryBlobStore
    private lateinit var clock: MutableClock
    private var parseResult: MimeParseResult =
        MimeParseResult.Parsed(
            ParsedMime(
                fromAddress = "sut@example.com",
                fromHeader = "sut@example.com",
                toHeader = null,
                subject = "hello",
                textBody = "body",
                htmlBody = null,
                headers = emptyList(),
                links = emptyList(),
                attachments = emptyList(),
            ),
        )
    private val parser =
        object : MimeParser {
            override fun parse(raw: ByteArray): MimeParseResult = parseResult
        }
    private lateinit var useCase: ReceiveInboundMessage
    private lateinit var inbox: Inbox

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        messages = InMemoryMessageRepository()
        blobs = InMemoryBlobStore()
        clock = MutableClock(Instant.parse("2026-08-29T12:00:00Z"))
        useCase =
            ReceiveInboundMessage(
                inboxes, messages, blobs, parser, clock, TestInboxConfig(mailDomain = "testinbox.local"),
            )
        inbox =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = ProjectId(UUID.randomUUID()),
                address = "test@testinbox.local",
                addressMode = AddressMode.GENERATED,
                state = InboxState.ACTIVE,
                createdAt = clock.now,
                expiresAt = clock.now.plusSeconds(600),
            )
        inboxes.insert(inbox)
    }

    private fun command(
        recipient: String = "test@testinbox.local",
        raw: ByteArray = "MIME".toByteArray(),
        providerMessageId: String? = null,
    ) = ReceiveInboundMessage.Command(recipient, "sut@example.com", recipient, raw, "local-smtp", providerMessageId)

    @Test
    fun `accepted delivery stores raw first, persists a visible message and notifies`() {
        val result = useCase.execute(command())
        val messageId = result.shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>().messageId
        val stored = messages.messages.single()
        stored.id shouldBe messageId
        stored.parseStatus shouldBe ParseStatus.OK
        blobs.get(stored.rawObjectKey) shouldNotBe null
        // Raw blob was written before the row was appended (ADR-005 order).
        blobs.putOrder.first() shouldBe stored.rawObjectKey
        messages.notifiedInboxes shouldBe listOf(inbox.id)
    }

    @Test
    fun `unknown recipient content is discarded and never stored (ADR-025)`() {
        val result = useCase.execute(command(recipient = "ghost@testinbox.local"))
        result.shouldBeInstanceOf<ReceiveInboundMessage.Result.Discarded>()
        messages.messages.shouldBeEmpty()
        blobs.blobs.keys.shouldBeEmpty()
    }

    @Test
    fun `expired inbox behaves like unknown recipient`() {
        inboxes.inboxes[inbox.id] = inbox.copy(state = InboxState.EXPIRED)
        useCase.execute(command()).shouldBeInstanceOf<ReceiveInboundMessage.Result.Discarded>()
        blobs.blobs.keys.shouldBeEmpty()
    }

    @Test
    fun `expiring inbox within grace still receives (ADR-009)`() {
        inboxes.inboxes[inbox.id] =
            inbox.copy(state = InboxState.EXPIRING, graceUntil = clock.now.plusSeconds(30))
        useCase.execute(command()).shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
    }

    @Test
    fun `parse failure persists the message with raw retained (ADR-005)`() {
        parseResult = MimeParseResult.Failed("boom")
        val result = useCase.execute(command())
        result.shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
        val stored = messages.messages.single()
        stored.parseStatus shouldBe ParseStatus.FAILED
        stored.parseError shouldBe "boom"
        stored.parsed shouldBe null
        blobs.get(stored.rawObjectKey) shouldNotBe null
    }

    @Test
    fun `identical content twice yields two messages with duplicate annotation, never suppression (ADR-019)`() {
        val raw = "identical".toByteArray()
        val first = useCase.execute(command(raw = raw))
            .shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
        clock.advanceSeconds(1)
        useCase.execute(command(raw = raw)).shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
        messages.messages.size shouldBe 2
        messages.messages[1].possibleDuplicateOfMessageId shouldBe first.messageId
        messages.messages[0].possibleDuplicateOfMessageId shouldBe null
        messages.messages[0].contentFingerprint shouldBe messages.messages[1].contentFingerprint
    }

    @Test
    fun `same provider delivery event is a no-op and its blobs are cleaned up`() {
        useCase.execute(command(providerMessageId = "evt-1"))
            .shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
        val blobCount = blobs.blobs.size
        useCase.execute(command(providerMessageId = "evt-1"))
            .shouldBeInstanceOf<ReceiveInboundMessage.Result.DuplicateEvent>()
        messages.messages.size shouldBe 1
        blobs.blobs.size shouldBe blobCount
    }

    @Test
    fun `attachments are stored under per-message keys before the row is appended`() {
        parseResult =
            MimeParseResult.Parsed(
                (parseResult as MimeParseResult.Parsed).content.copy(
                    attachments = listOf(MimeAttachment("invoice.pdf", "application/pdf", byteArrayOf(1, 2))),
                ),
            )
        val result = useCase.execute(command())
        result.shouldBeInstanceOf<ReceiveInboundMessage.Result.Accepted>()
        val stored = messages.messages.single()
        val attachment = stored.attachments.single()
        attachment.fileName shouldBe "invoice.pdf"
        attachment.objectKey.contains("/attachments/") shouldBe true
        blobs.get(attachment.objectKey)?.toList() shouldBe listOf<Byte>(1, 2)
    }
}
