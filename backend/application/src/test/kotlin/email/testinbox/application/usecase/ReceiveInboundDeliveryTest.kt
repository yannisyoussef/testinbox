package email.testinbox.application.usecase

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.MutableClock
import email.testinbox.application.RollbackTx
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReceiveInboundDeliveryTest {
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
    private lateinit var useCase: ReceiveInboundDelivery
    private lateinit var inbox: Inbox

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        messages = InMemoryMessageRepository()
        blobs = InMemoryBlobStore()
        clock = MutableClock(Instant.parse("2026-08-29T12:00:00Z"))
        useCase = ReceiveInboundDelivery(inboxes, messages, blobs, parser, RollbackTx(messages), clock)
        inbox = provisionInbox("test@testinbox.local")
    }

    private fun provisionInbox(
        address: String,
        workspace: WorkspaceId = workspaceId,
    ): Inbox {
        val created =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspace,
                projectId = ProjectId(UUID.randomUUID()),
                address = address,
                addressMode = AddressMode.GENERATED,
                state = InboxState.ACTIVE,
                createdAt = clock.now,
                expiresAt = clock.now.plusSeconds(600),
            )
        inboxes.insert(created)
        return created
    }

    private fun command(
        recipients: List<String> = listOf("test@testinbox.local"),
        raw: ByteArray = "MIME".toByteArray(),
        providerMessageId: String? = null,
        provider: String = "local-smtp",
    ) = ReceiveInboundDelivery.Command("sut@example.com", recipients, raw, provider, providerMessageId)

    @Test
    fun `accepted delivery stores raw first, persists a visible message and notifies`() {
        val result = useCase.execute(command())
        val messageId = result.accepted.single()
        val stored = messages.messages.single()
        stored.id shouldBe messageId
        stored.parseStatus shouldBe ParseStatus.OK
        blobs.get(stored.rawObjectKey) shouldNotBe null
        // Raw blob was written before the row was appended (ADR-005 order).
        blobs.putOrder.first() shouldBe stored.rawObjectKey
        messages.notifiedInboxes shouldBe listOf(inbox.id)
    }

    @Test
    fun `one event with two recipients persists one message per recipient and notifies both`() {
        val second = provisionInbox("second@testinbox.local")
        val result = useCase.execute(command(recipients = listOf(inbox.address, second.address)))
        result.accepted.size shouldBe 2
        result.discardedRecipients shouldBe 0
        messages.messages.map { it.envelopeTo } shouldContainExactly listOf(inbox.address, second.address)
        messages.notifiedInboxes shouldContainExactly listOf(inbox.id, second.id)
        // Per-message blob ownership: distinct raw keys, no shared object.
        messages.messages
            .map { it.rawObjectKey }
            .toSet()
            .size shouldBe 2
    }

    @Test
    fun `unknown recipient in a multi-recipient event is discarded while the known one is delivered`() {
        val result = useCase.execute(command(recipients = listOf(inbox.address, "ghost@testinbox.local")))
        result.accepted.size shouldBe 1
        result.discardedRecipients shouldBe 1
        messages.messages.single().envelopeTo shouldBe inbox.address
        // No blob was written for the unknown recipient (ADR-025).
        blobs.blobs.keys.size shouldBe 1
    }

    @Test
    fun `an event addressed only to unknown recipients stores nothing at all (ADR-025)`() {
        val result = useCase.execute(command(recipients = listOf("ghost@testinbox.local")))
        result.accepted.shouldBeEmpty()
        result.discardedRecipients shouldBe 1
        messages.messages.shouldBeEmpty()
        blobs.blobs.keys.shouldBeEmpty()
    }

    @Test
    fun `a persistence failure for one recipient commits nothing for any recipient`() {
        val second = provisionInbox("second@testinbox.local")
        messages.failAppendForRecipient = second.address
        shouldThrow<IllegalStateException> {
            useCase.execute(command(recipients = listOf(inbox.address, second.address)))
        }
        // Partial delivery is the failure mode this design exists to prevent.
        messages.messages.shouldBeEmpty()
        messages.notifiedInboxes.shouldBeEmpty()
    }

    @Test
    fun `retry after a failed event delivers every recipient exactly once`() {
        val second = provisionInbox("second@testinbox.local")
        messages.failAppendForRecipient = second.address
        shouldThrow<IllegalStateException> {
            useCase.execute(command(recipients = listOf(inbox.address, second.address)))
        }
        messages.failAppendForRecipient = null
        val retry = useCase.execute(command(recipients = listOf(inbox.address, second.address)))
        retry.accepted.size shouldBe 2
        messages.messages.count { it.envelopeTo == inbox.address } shouldBe 1
        messages.messages.count { it.envelopeTo == second.address } shouldBe 1
    }

    @Test
    fun `duplicate RCPT of the same recipient yields a single message`() {
        val result = useCase.execute(command(recipients = listOf(inbox.address, inbox.address.uppercase())))
        result.accepted.size shouldBe 1
        messages.messages.size shouldBe 1
    }

    @Test
    fun `recipients in different workspaces each get their own workspace-scoped message`() {
        val otherWorkspace = WorkspaceId(UUID.randomUUID())
        val foreign = provisionInbox("other@testinbox.local", workspace = otherWorkspace)
        useCase.execute(command(recipients = listOf(inbox.address, foreign.address)))
        messages.messages.single { it.envelopeTo == inbox.address }.workspaceId shouldBe workspaceId
        messages.messages.single { it.envelopeTo == foreign.address }.workspaceId shouldBe otherWorkspace
        // Object keys are workspace-prefixed, so no cross-tenant blob path exists.
        messages.messages.forEach { it.rawObjectKey.startsWith("${it.workspaceId}/") shouldBe true }
    }

    @Test
    fun `expired inbox behaves like unknown recipient`() {
        inboxes.inboxes[inbox.id] = inbox.copy(state = InboxState.EXPIRED)
        useCase.execute(command()).accepted.shouldBeEmpty()
        blobs.blobs.keys.shouldBeEmpty()
    }

    @Test
    fun `expiring inbox within grace still receives (ADR-009)`() {
        inboxes.inboxes[inbox.id] =
            inbox.copy(state = InboxState.EXPIRING, graceUntil = clock.now.plusSeconds(30))
        useCase.execute(command()).accepted.size shouldBe 1
    }

    @Test
    fun `parse failure persists the message with raw retained (ADR-005)`() {
        parseResult = MimeParseResult.Failed("boom")
        useCase.execute(command()).accepted.size shouldBe 1
        val stored = messages.messages.single()
        stored.parseStatus shouldBe ParseStatus.FAILED
        stored.parseError shouldBe "boom"
        stored.parsed shouldBe null
        blobs.get(stored.rawObjectKey) shouldNotBe null
    }

    @Test
    fun `identical content twice yields two messages with duplicate annotation, never suppression (ADR-019)`() {
        val raw = "identical".toByteArray()
        val first = useCase.execute(command(raw = raw)).accepted.single()
        clock.advanceSeconds(1)
        useCase.execute(command(raw = raw)).accepted.size shouldBe 1
        messages.messages.size shouldBe 2
        messages.messages[1].possibleDuplicateOfMessageId shouldBe first
        messages.messages[0].possibleDuplicateOfMessageId shouldBe null
        messages.messages[0].contentFingerprint shouldBe messages.messages[1].contentFingerprint
    }

    @Test
    fun `identical MIME to two recipients is not cross-suppressed (ADR-019)`() {
        val second = provisionInbox("second@testinbox.local")
        useCase.execute(command(recipients = listOf(inbox.address, second.address))).accepted.size shouldBe 2
        // Same fingerprint, different inboxes: neither annotates the other.
        messages.messages[0].contentFingerprint shouldBe messages.messages[1].contentFingerprint
        messages.messages.forEach { it.possibleDuplicateOfMessageId shouldBe null }
    }

    @Test
    fun `reprocessing the same provider event for the same recipient is a no-op (ADR-026)`() {
        useCase.execute(command(provider = "ses", providerMessageId = "evt-1")).accepted.size shouldBe 1
        val blobCount = blobs.blobs.size
        val replay = useCase.execute(command(provider = "ses", providerMessageId = "evt-1"))
        replay.accepted.shouldBeEmpty()
        replay.duplicateRecipients shouldBe 1
        messages.messages.size shouldBe 1
        // The no-op's own blobs are cleaned up; the original's are untouched.
        blobs.blobs.size shouldBe blobCount
    }

    @Test
    fun `one provider event fanning out to several recipients is not self-deduplicated (ADR-026)`() {
        val second = provisionInbox("second@testinbox.local")
        val result =
            useCase.execute(
                command(
                    recipients = listOf(inbox.address, second.address),
                    provider = "ses",
                    providerMessageId = "evt-multi",
                ),
            )
        result.accepted.size shouldBe 2
        result.duplicateRecipients shouldBe 0
        messages.messages.map { it.envelopeTo } shouldContainExactly listOf(inbox.address, second.address)
    }

    @Test
    fun `distinct provider events with identical MIME are both observable (ADR-019)`() {
        val raw = "identical".toByteArray()
        useCase.execute(command(raw = raw, provider = "ses", providerMessageId = "evt-1")).accepted.size shouldBe 1
        useCase.execute(command(raw = raw, provider = "ses", providerMessageId = "evt-2")).accepted.size shouldBe 1
        messages.messages.size shouldBe 2
    }

    @Test
    fun `attachments are stored under per-message keys before the row is appended`() {
        parseResult =
            MimeParseResult.Parsed(
                (parseResult as MimeParseResult.Parsed).content.copy(
                    attachments = listOf(MimeAttachment("invoice.pdf", "application/pdf", byteArrayOf(1, 2))),
                ),
            )
        useCase.execute(command()).accepted.size shouldBe 1
        val attachment =
            messages.messages
                .single()
                .attachments
                .single()
        attachment.fileName shouldBe "invoice.pdf"
        attachment.objectKey.contains("/attachments/") shouldBe true
        blobs.get(attachment.objectKey)?.toList() shouldBe listOf<Byte>(1, 2)
    }
}
