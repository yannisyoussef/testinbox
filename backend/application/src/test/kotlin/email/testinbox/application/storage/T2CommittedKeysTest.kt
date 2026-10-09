package email.testinbox.application.storage

import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryStorage
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.ObjectKeys
import email.testinbox.application.usecase.StorageAdmissionCandidate
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The contract's T2 assertion (§2.4, T2 row; TI-STORAGE-006E PR D): the rows T2
 * inserts must name EXACTLY the keys T1 reserved and the event uploaded. A row
 * set that leaves an uploaded key out would make it a live object no row
 * charges; T2 fences the event instead.
 */
class T2CommittedKeysTest {
    private val clock = MutableClock(Instant.parse("2026-10-08T12:00:00Z"))
    private val inboxes = InMemoryInboxRepository()
    private val messages = InMemoryMessageRepository()
    private val storage = InMemoryStorage(inboxes, messages, clock)
    private val ws = WorkspaceId(UUID.randomUUID())
    private val inbox =
        Inbox(
            InboxId(UUID.randomUUID()),
            ws,
            ProjectId(UUID.randomUUID()),
            "a@x",
            AddressMode.GENERATED,
            InboxState.ACTIVE,
            clock.now,
            clock.now.plusSeconds(600),
        ).also { inboxes.inboxes[it.id] = it }

    private fun plan(): CopyPlan {
        val message = MessageId(UUID.randomUUID())
        val raw = ObjectKeys.raw(ws, inbox.id, message)
        val attachment = ObjectKeys.attachment(ws, inbox.id, message, AttachmentId(UUID.randomUUID()))
        return CopyPlan(
            StorageAdmissionCandidate(message, ws, inbox.id, listOf(raw, attachment)),
            listOf(
                raw to byteArrayOf(1),
                attachment to byteArrayOf(2),
            ),
        )
    }

    private fun row(
        plan: CopyPlan,
        withAttachment: Boolean,
    ) = Message(
        plan.messageId,
        ws,
        inbox.id,
        clock.now,
        "local-smtp",
        null,
        "s@x",
        inbox.address,
        plan.candidate.objectKeys[0],
        1,
        UUID.randomUUID().toString(),
        null,
        ParseStatus.OK,
        null,
        null,
        if (withAttachment) {
            listOf(
                Attachment(
                    AttachmentId(UUID.randomUUID()),
                    plan.messageId,
                    "a",
                    "application/octet-stream",
                    1,
                    plan.candidate.objectKeys[1],
                ),
            )
        } else {
            emptyList()
        },
    )

    @Test
    fun `rows that leave an uploaded key out are fenced - a live object no row would charge`() {
        val copy = plan()
        shouldThrow<StorageUnavailableException> {
            storage.guarded(NoopTx).ingest(copy.bytes, listOf(copy)) { admitted ->
                admitted.forEach { messages.messages += row(it, withAttachment = false) }
                admitted.associate { it.messageId to true }
            }
        }.reason shouldBe StorageUnavailableReason.COMMIT_FENCED
    }

    @Test
    fun `rows that name exactly the reserved keys commit`() {
        val copy = plan()
        storage
            .guarded(NoopTx)
            .ingest(copy.bytes, listOf(copy)) { admitted ->
                admitted.forEach { messages.messages += row(it, withAttachment = true) }
                admitted.associate { it.messageId to true }
            }.appended shouldBe listOf(copy.messageId)
    }
}
