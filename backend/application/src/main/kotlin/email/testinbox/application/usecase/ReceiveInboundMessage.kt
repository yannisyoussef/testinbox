package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.Sha256
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.MessageId
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * The single inbound write path (ADR-024), upholding:
 * - raw MIME stored before parsing/persistence (ADR-005);
 * - no content-based dedup — every completed delivery is its own row,
 *   annotated (never suppressed) on shared fingerprints (ADR-019);
 * - provider-event dedup only via (provider, providerMessageId);
 * - unknown-recipient content discarded in-process, never stored (ADR-025);
 * - insert + pg_notify atomic in one transaction via the repository
 *   contract (ADR-020).
 *
 * Infrastructure failures propagate as exceptions so the SMTP adapter can
 * soft-fail (4xx) and the sender retries — never a silent drop.
 */
class ReceiveInboundMessage(
    private val inboxes: InboxRepository,
    private val messages: MessageRepository,
    private val blobs: BlobStore,
    private val parser: MimeParser,
    private val clock: Clock,
    private val config: TestInboxConfig,
) {
    data class Command(
        val recipientAddress: String,
        val envelopeFrom: String?,
        val envelopeTo: String,
        val raw: ByteArray,
        val provider: String,
        val providerMessageId: String? = null,
    )

    sealed interface Result {
        data class Accepted(val messageId: MessageId) : Result

        /** Unknown/expired recipient — content already discarded, only metadata logged (ADR-025). */
        data object Discarded : Result

        /** Reprocessing of an already-recorded provider delivery event (ADR-019). */
        data object DuplicateEvent : Result
    }

    fun execute(command: Command): Result {
        val now = clock.instant()
        val address = command.recipientAddress.trim().lowercase()
        val inbox = inboxes.findReceivableByAddress(address)
        if (inbox == null || !inbox.canReceiveAt(now)) {
            // ADR-025: metadata only — hashed recipient token, sender domain, size. Never content.
            log.info(
                "smtp_unknown_recipient_discard recipientHash={} senderDomain={} sizeBytes={}",
                Sha256.hex(address).take(16),
                command.envelopeFrom?.substringAfter('@', missingDelimiterValue = "unknown") ?: "unknown",
                command.raw.size,
            )
            return Result.Discarded
        }

        val messageId = MessageId(UUID.randomUUID())
        val rawKey = ObjectKeys.raw(inbox.workspaceId, inbox.id, messageId)
        // ADR-005: raw bytes stored durably before parsing is attempted.
        blobs.put(rawKey, command.raw, "message/rfc822")

        val fingerprint = Sha256.hex(command.raw)
        val parseResult = parser.parse(command.raw)
        val possibleDuplicateOf = messages.findEarliestIdByFingerprint(inbox.id, fingerprint)

        val attachments = mutableListOf<Attachment>()
        val parsed: ParsedContent?
        val parseStatus: ParseStatus
        val parseError: String?
        when (parseResult) {
            is MimeParseResult.Parsed -> {
                parseStatus = ParseStatus.OK
                parseError = null
                for (mimeAttachment in parseResult.content.attachments) {
                    val attachmentId = AttachmentId(UUID.randomUUID())
                    val key = ObjectKeys.attachment(inbox.workspaceId, inbox.id, messageId, attachmentId)
                    blobs.put(key, mimeAttachment.bytes, mimeAttachment.contentType ?: "application/octet-stream")
                    attachments +=
                        Attachment(
                            id = attachmentId,
                            messageId = messageId,
                            fileName = mimeAttachment.fileName,
                            contentType = mimeAttachment.contentType,
                            sizeBytes = mimeAttachment.bytes.size.toLong(),
                            objectKey = key,
                        )
                }
                parsed =
                    ParsedContent(
                        fromAddress = parseResult.content.fromAddress,
                        fromHeader = parseResult.content.fromHeader,
                        toHeader = parseResult.content.toHeader,
                        subject = parseResult.content.subject,
                        textBody = parseResult.content.textBody,
                        htmlBody = parseResult.content.htmlBody,
                        headers = parseResult.content.headers,
                        links = parseResult.content.links,
                    )
            }
            is MimeParseResult.Failed -> {
                parseStatus = ParseStatus.FAILED
                parseError = parseResult.reason
                parsed = null
            }
        }

        val message =
            Message(
                id = messageId,
                workspaceId = inbox.workspaceId,
                inboxId = inbox.id,
                receivedAt = now,
                provider = command.provider,
                providerMessageId = command.providerMessageId,
                envelopeFrom = command.envelopeFrom,
                envelopeTo = command.envelopeTo,
                rawObjectKey = rawKey,
                rawSizeBytes = command.raw.size.toLong(),
                contentFingerprint = fingerprint,
                possibleDuplicateOfMessageId = possibleDuplicateOf,
                parseStatus = parseStatus,
                parseError = parseError,
                parsed = parsed,
                attachments = attachments,
            )

        return when (messages.appendVisible(message)) {
            AppendOutcome.Appended -> Result.Accepted(messageId)
            AppendOutcome.DuplicateProviderEvent -> {
                // Same provider event reprocessed: clean up the blobs we just wrote for the no-op.
                blobs.delete(rawKey)
                attachments.forEach { blobs.delete(it.objectKey) }
                Result.DuplicateEvent
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ReceiveInboundMessage::class.java)
    }
}
