package email.testinbox.application.query

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Logical expiry is independent of physical deletion (filesystem-containment
 * contract §5.4; TI-STORAGE-006E PR D). Paced retention may keep an expired or
 * deleted inbox's rows and blobs for a while; none of it is ever served: a
 * message, its raw MIME and its attachments read as absent (`404`), and a list
 * is empty. ACTIVE and EXPIRING inboxes are served as before.
 */
class MessageReadGatingTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val ws = WorkspaceId(UUID.randomUUID())
    private val inboxes = InMemoryInboxRepository()
    private val messages = InMemoryMessageRepository()
    private val blobs = InMemoryBlobStore()
    private val queries = MessageQueries(messages, blobs, inboxes)

    private fun inboxWithMessage(state: InboxState): Pair<Inbox, Message> {
        val inbox =
            Inbox(
                InboxId(UUID.randomUUID()),
                ws,
                ProjectId(UUID.randomUUID()),
                "${UUID.randomUUID()}@x",
                AddressMode.GENERATED,
                state,
                now,
                now,
            )
        inboxes.inboxes[inbox.id] = inbox
        val key = "k-${UUID.randomUUID()}"
        blobs.put(key, byteArrayOf(1, 2), "message/rfc822")
        val message =
            Message(
                MessageId(UUID.randomUUID()),
                ws,
                inbox.id,
                now,
                "local-smtp",
                null,
                "a@x",
                inbox.address,
                key,
                2,
                UUID.randomUUID().toString(),
                null,
                ParseStatus.OK,
                null,
                ParsedContent("a@x", null, null, "s", "b", null, emptyList(), emptyList()),
                emptyList(),
            )
        messages.messages += message
        return inbox to message
    }

    @Test
    fun `an EXPIRED or DELETED inbox serves nothing - message, raw MIME, attachments read absent and the list is empty`() {
        listOf(InboxState.EXPIRED, InboxState.DELETED).forEach { state ->
            val (inbox, message) = inboxWithMessage(state)
            queries.get(ws, message.id) shouldBe null
            queries.rawMime(ws, message.id) shouldBe null
            queries.attachments(ws, message.id) shouldBe null
            queries.listPage(ws, inbox.id, null, 50) shouldBe emptyList()
        }
    }

    @Test
    fun `ACTIVE and EXPIRING inboxes are served exactly as before`() {
        listOf(InboxState.ACTIVE, InboxState.EXPIRING).forEach { state ->
            val (inbox, message) = inboxWithMessage(state)
            queries.get(ws, message.id) shouldBe message
            queries.rawMime(ws, message.id)?.toList() shouldBe listOf<Byte>(1, 2)
            queries.listPage(ws, inbox.id, null, 50).map { it.id } shouldBe listOf(message.id)
        }
    }
}
