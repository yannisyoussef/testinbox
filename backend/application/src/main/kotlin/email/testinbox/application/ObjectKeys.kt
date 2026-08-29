package email.testinbox.application

import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId

/**
 * Object-storage key layout (docs/architecture/data-ownership.md):
 * `{workspace_id}/{inbox_id}/{message_id}/raw.eml` and
 * `.../attachments/{attachment_id}`. Per-message ownership keys — deleting
 * one inbox's prefix can never touch another inbox's blobs.
 */
object ObjectKeys {
    fun inboxPrefix(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
    ): String = "$workspaceId/$inboxId/"

    fun raw(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        messageId: MessageId,
    ): String = "$workspaceId/$inboxId/$messageId/raw.eml"

    fun attachment(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        messageId: MessageId,
        attachmentId: AttachmentId,
    ): String = "$workspaceId/$inboxId/$messageId/attachments/$attachmentId"

    /** Extracts the message id segment from any per-message key, or null. */
    fun messageIdOf(key: String): String? = key.split('/').getOrNull(2)
}
