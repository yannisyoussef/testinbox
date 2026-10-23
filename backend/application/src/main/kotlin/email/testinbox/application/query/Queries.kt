package email.testinbox.application.query

import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.MessageRepository
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.Message

/**
 * Thin read-side services (ADR-024 allows simple reads to bypass use-case
 * ceremony). Every lookup is workspace-scoped; a cross-tenant id yields
 * null, which the API maps to 404 (never 403 — no existence leakage).
 */
class InboxQueries(
    private val inboxes: InboxRepository,
) {
    fun get(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
    ): Inbox? = inboxes.findById(workspaceId, inboxId)
}

/**
 * Message reads. TI-STORAGE-006E PR D (filesystem-containment contract §5.4):
 * logical expiry is independent of physical deletion. Paced retention may
 * keep an expired or deleted inbox's rows and blobs for a while, but they are
 * never SERVED once its state says so: a message, its raw MIME and its
 * attachments read as absent (`404`, as for a deleted message), and a list of
 * its messages is empty.
 */
class MessageQueries(
    private val messages: MessageRepository,
    private val blobs: BlobStore,
    private val inboxes: InboxRepository,
) {
    fun get(
        workspaceId: WorkspaceId,
        messageId: MessageId,
    ): Message? = readable(workspaceId, messages.findById(workspaceId, messageId))

    fun listPage(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        after: MessageCursor?,
        limit: Int,
    ): List<Message> =
        if (servable(inboxes.findById(workspaceId, inboxId))) messages.listPage(workspaceId, inboxId, after, limit) else emptyList()

    fun rawMime(
        workspaceId: WorkspaceId,
        messageId: MessageId,
    ): ByteArray? {
        val message = get(workspaceId, messageId) ?: return null
        return blobs.get(message.rawObjectKey)
    }

    fun attachments(
        workspaceId: WorkspaceId,
        messageId: MessageId,
    ): List<Attachment>? = get(workspaceId, messageId)?.attachments

    fun attachmentBytes(
        workspaceId: WorkspaceId,
        messageId: MessageId,
        attachmentId: AttachmentId,
    ): Pair<Attachment, ByteArray>? {
        val message = get(workspaceId, messageId) ?: return null
        val attachment = message.attachments.firstOrNull { it.id == attachmentId } ?: return null
        val bytes = blobs.get(attachment.objectKey) ?: return null
        return attachment to bytes
    }

    private fun readable(
        workspaceId: WorkspaceId,
        message: Message?,
    ): Message? = message?.takeIf { servable(inboxes.findById(workspaceId, it.inboxId)) }

    private fun servable(inbox: Inbox?): Boolean =
        inbox != null && inbox.state != email.testinbox.domain.inbox.InboxState.EXPIRED &&
            inbox.state != email.testinbox.domain.inbox.InboxState.DELETED
}
