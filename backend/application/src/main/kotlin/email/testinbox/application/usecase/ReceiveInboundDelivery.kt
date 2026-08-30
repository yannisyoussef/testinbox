package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.Sha256
import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.MessageId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * The single inbound write path (ADR-024). One invocation represents ONE
 * inbound provider event — one SMTP `DATA` transaction, or one provider
 * delivery notification — which may fan out to several envelope recipients
 * (ADR-026).
 *
 * All recipient rows of an event are committed in ONE database transaction,
 * so an event is never partially visible: a caller retrying after a failure
 * can never cause an infrastructure-induced duplicate for a recipient that
 * "already worked" (see docs/architecture/inbound-mail-flow.md).
 *
 * Upholds:
 * - raw MIME stored before persistence, per recipient message (ADR-005);
 * - no content-based dedup — annotation only (ADR-019);
 * - provider-event dedup scoped to (provider, providerMessageId, recipient) (ADR-026);
 * - unknown-recipient content discarded in-process, never stored (ADR-025);
 * - inserts + pg_notify atomic in the same transaction (ADR-020).
 *
 * Infrastructure failures propagate as exceptions so the SMTP adapter can
 * soft-fail (4xx) for the whole transaction and the sender retries — never a
 * silent drop, never a partial commit.
 */
class ReceiveInboundDelivery(
    private val inboxes: InboxRepository,
    private val messages: MessageRepository,
    private val blobs: BlobStore,
    private val parser: MimeParser,
    private val transactions: TransactionRunner,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    data class Command(
        val envelopeFrom: String?,
        val recipients: List<String>,
        val raw: ByteArray,
        val provider: String,
        val providerMessageId: String? = null,
    )

    data class Result(
        /** Message ids persisted by this event, one per accepted recipient. */
        val accepted: List<MessageId>,
        /** Recipients that resolved to no receivable inbox; their content was discarded. */
        val discardedRecipients: Int,
        /** Recipients already recorded for this provider event — reprocessing no-ops (ADR-026). */
        val duplicateRecipients: Int,
        /** Recipients whose inbound rate budget was exhausted; their content was discarded. */
        val rateLimitedRecipients: Int = 0,
    )

    private data class Prepared(
        val inbox: Inbox,
        val recipient: String,
        val messageId: MessageId,
        val rawKey: String,
        val attachments: List<Attachment>,
        val parseStatus: ParseStatus,
        val parseError: String?,
        val parsed: ParsedContent?,
    ) {
        val objectKeys: List<String> get() = listOf(rawKey) + attachments.map { it.objectKey }
    }

    fun execute(command: Command): Result {
        val now = clock.instant()
        val recipients = command.recipients.map { it.trim().lowercase() }.distinct()
        val fingerprint = Sha256.hex(command.raw)
        // One event, one parse: recipients of the same event share the bytes.
        val parseResult = parser.parse(command.raw)

        var discarded = 0
        var rateLimited = 0
        val prepared = mutableListOf<Prepared>()
        for (recipient in recipients) {
            val inbox = inboxes.findReceivableByAddress(recipient)
            if (inbox == null || !inbox.canReceiveAt(now)) {
                logDiscard(recipient, command, "unknown_recipient")
                discarded++
                continue
            }
            // ADR-027 §4: the inbound budget is charged per workspace AND per
            // inbox, so a flood against one guessed EXACT address cannot consume
            // the whole workspace's allowance. Charged only after the recipient
            // resolves, so the ADR-025 unknown path never reaches the limiter.
            val workspaceBudget = rateLimiter.tryConsume(inbox.workspaceId, RateCategory.INGEST)
            val inboxBudget = rateLimiter.tryConsume(inbox.workspaceId, RateCategory.INGEST, inbox.id)
            if (!workspaceBudget.allowed || !inboxBudget.allowed) {
                // Discarded in-process, exactly as ADR-025 discards unknown
                // recipients. The SMTP reply is unchanged (ADR-027 §1): making a
                // refusal visible here would rebuild the enumeration oracle.
                logDiscard(recipient, command, "ingest_rate_limited")
                rateLimited++
                continue
            }
            prepared += prepare(inbox, recipient, command, parseResult)
        }
        if (prepared.isEmpty()) return Result(emptyList(), discarded, 0, rateLimited)

        // One transaction for every recipient row of this event: all rows and
        // their pg_notify calls commit together, or none of them do.
        val outcomes =
            transactions.required {
                prepared.map { candidate ->
                    val message = candidate.toMessage(command, fingerprint, now)
                    candidate to messages.appendVisible(message)
                }
            }

        val accepted = mutableListOf<MessageId>()
        var duplicates = 0
        for ((candidate, outcome) in outcomes) {
            when (outcome) {
                AppendOutcome.Appended -> {
                    accepted += candidate.messageId
                }

                AppendOutcome.DuplicateProviderEvent -> {
                    duplicates++
                    // Reprocessed event: the blobs written for this no-op are ours alone
                    // (per-message keys), so removing them cannot touch the original row.
                    candidate.objectKeys.forEach(blobs::delete)
                }
            }
        }
        return Result(accepted, discarded, duplicates, rateLimited)
    }

    private fun prepare(
        inbox: Inbox,
        recipient: String,
        command: Command,
        parseResult: MimeParseResult,
    ): Prepared {
        val messageId = MessageId(UUID.randomUUID())
        val rawKey = ObjectKeys.raw(inbox.workspaceId, inbox.id, messageId)
        // ADR-005: raw bytes stored durably before the row is persisted.
        blobs.put(rawKey, command.raw, "message/rfc822")

        return when (parseResult) {
            is MimeParseResult.Parsed -> {
                val attachments =
                    parseResult.content.attachments.map { mimeAttachment ->
                        val attachmentId = AttachmentId(UUID.randomUUID())
                        val key = ObjectKeys.attachment(inbox.workspaceId, inbox.id, messageId, attachmentId)
                        blobs.put(key, mimeAttachment.bytes, mimeAttachment.contentType ?: "application/octet-stream")
                        Attachment(
                            id = attachmentId,
                            messageId = messageId,
                            fileName = mimeAttachment.fileName,
                            contentType = mimeAttachment.contentType,
                            sizeBytes = mimeAttachment.bytes.size.toLong(),
                            objectKey = key,
                        )
                    }
                Prepared(
                    inbox = inbox,
                    recipient = recipient,
                    messageId = messageId,
                    rawKey = rawKey,
                    attachments = attachments,
                    parseStatus = ParseStatus.OK,
                    parseError = null,
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
                        ),
                )
            }

            is MimeParseResult.Failed -> {
                Prepared(
                    inbox = inbox,
                    recipient = recipient,
                    messageId = messageId,
                    rawKey = rawKey,
                    attachments = emptyList(),
                    parseStatus = ParseStatus.FAILED,
                    parseError = parseResult.reason,
                    parsed = null,
                )
            }
        }
    }

    private fun Prepared.toMessage(
        command: Command,
        fingerprint: String,
        now: java.time.Instant,
    ): Message =
        Message(
            id = messageId,
            workspaceId = inbox.workspaceId,
            inboxId = inbox.id,
            receivedAt = now,
            provider = command.provider,
            providerMessageId = command.providerMessageId,
            envelopeFrom = command.envelopeFrom,
            envelopeTo = recipient,
            rawObjectKey = rawKey,
            rawSizeBytes = command.raw.size.toLong(),
            contentFingerprint = fingerprint,
            // Annotation, never suppression (ADR-019); read inside the same transaction.
            possibleDuplicateOfMessageId = messages.findEarliestIdByFingerprint(inbox.id, fingerprint),
            parseStatus = parseStatus,
            parseError = parseError,
            parsed = parsed,
            attachments = attachments,
        )

    private fun logDiscard(
        recipient: String,
        command: Command,
        reason: String,
    ) {
        // ADR-025: metadata only — hashed recipient token, sender domain, size. Never content.
        log.info(
            "smtp_discard reason={} recipientHash={} senderDomain={} sizeBytes={}",
            reason,
            Sha256.hex(recipient).take(16),
            command.envelopeFrom?.substringAfter('@', missingDelimiterValue = "unknown") ?: "unknown",
            command.raw.size,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(ReceiveInboundDelivery::class.java)
    }
}
