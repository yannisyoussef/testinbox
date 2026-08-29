package email.testinbox.domain.message

import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import java.time.Instant

enum class ParseStatus { OK, FAILED }

data class EmailHeader(
    val name: String,
    val value: String,
)

data class EmailLink(
    val href: String,
    val text: String?,
)

data class Attachment(
    val id: AttachmentId,
    val messageId: MessageId,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long,
    val objectKey: String,
)

/** Parsed fields — absent entirely when parsing failed (ADR-005: raw MIME always survives). */
data class ParsedContent(
    val fromAddress: String?,
    val fromHeader: String?,
    val toHeader: String?,
    val subject: String?,
    val textBody: String?,
    val htmlBody: String?,
    val headers: List<EmailHeader>,
    val links: List<EmailLink>,
)

/**
 * A received email, faithful to what was accepted (ADR-019): every completed
 * inbound transaction is its own Message. [contentFingerprint] and
 * [possibleDuplicateOfMessageId] are annotation metadata only — never a
 * suppression key.
 */
data class Message(
    val id: MessageId,
    val workspaceId: WorkspaceId,
    val inboxId: InboxId,
    val receivedAt: Instant,
    val provider: String,
    /** Stable per-delivery provider event id; sole legal dedup key (ADR-003/019). Null for local SMTP. */
    val providerMessageId: String?,
    val envelopeFrom: String?,
    val envelopeTo: String,
    val rawObjectKey: String,
    val rawSizeBytes: Long,
    /** SHA-256 of the raw MIME bytes, informational (ADR-019 annotation, not suppression). */
    val contentFingerprint: String,
    val possibleDuplicateOfMessageId: MessageId?,
    val parseStatus: ParseStatus,
    val parseError: String?,
    val parsed: ParsedContent?,
    val attachments: List<Attachment>,
)
