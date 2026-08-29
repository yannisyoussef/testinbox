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
class InboxQueries(private val inboxes: InboxRepository) {
    fun get(workspaceId: WorkspaceId, inboxId: InboxId): Inbox? = inboxes.findById(workspaceId, inboxId)
}

class MessageQueries(
    private val messages: MessageRepository,
    private val blobs: BlobStore,
) {
    fun get(workspaceId: WorkspaceId, messageId: MessageId): Message? =
        messages.findById(workspaceId, messageId)

    fun listPage(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        after: MessageCursor?,
        limit: Int,
    ): List<Message> = messages.listPage(workspaceId, inboxId, after, limit)

    fun rawMime(workspaceId: WorkspaceId, messageId: MessageId): ByteArray? {
        val message = messages.findById(workspaceId, messageId) ?: return null
        return blobs.get(message.rawObjectKey)
    }

    fun attachments(workspaceId: WorkspaceId, messageId: MessageId): List<Attachment>? =
        messages.findById(workspaceId, messageId)?.attachments

    fun attachmentBytes(
        workspaceId: WorkspaceId,
        messageId: MessageId,
        attachmentId: AttachmentId,
    ): Pair<Attachment, ByteArray>? {
        val message = messages.findById(workspaceId, messageId) ?: return null
        val attachment = message.attachments.firstOrNull { it.id == attachmentId } ?: return null
        val bytes = blobs.get(attachment.objectKey) ?: return null
        return attachment to bytes
    }
}
