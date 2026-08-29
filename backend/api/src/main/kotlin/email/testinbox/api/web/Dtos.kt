package email.testinbox.api.web

import email.testinbox.application.port.MessageCursor
import email.testinbox.domain.MessageId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class CreateInboxRequest(
    val addressMode: String? = null,
    val ttlSeconds: Long? = null,
    val aliasHint: String? = null,
    val localPart: String? = null,
)

data class InboxDto(
    val id: UUID,
    val address: String,
    val addressMode: String,
    val state: String,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    companion object {
        fun from(inbox: Inbox): InboxDto =
            InboxDto(
                id = inbox.id.value,
                address = inbox.address,
                addressMode = inbox.addressMode.name,
                state = inbox.state.name,
                createdAt = inbox.createdAt,
                expiresAt = inbox.expiresAt,
            )
    }
}

data class EmailHeaderDto(
    val name: String,
    val value: String,
)

data class EmailLinkDto(
    val href: String,
    val text: String?,
)

data class AttachmentMetaDto(
    val id: UUID,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long,
)

data class MessageDto(
    val id: UUID,
    val inboxId: UUID,
    val receivedAt: Instant,
    val envelopeFrom: String?,
    val envelopeTo: String,
    val parseStatus: String,
    val parseError: String?,
    val from: String?,
    val fromHeader: String?,
    val toHeader: String?,
    val subject: String?,
    val textBody: String?,
    val htmlBody: String?,
    val headers: List<EmailHeaderDto>?,
    val links: List<EmailLinkDto>?,
    val attachments: List<AttachmentMetaDto>,
    val contentFingerprint: String,
    val possibleDuplicateOfMessageId: UUID?,
    val rawSizeBytes: Long,
) {
    companion object {
        fun from(message: Message): MessageDto =
            MessageDto(
                id = message.id.value,
                inboxId = message.inboxId.value,
                receivedAt = message.receivedAt,
                envelopeFrom = message.envelopeFrom,
                envelopeTo = message.envelopeTo,
                parseStatus = message.parseStatus.name,
                parseError = message.parseError,
                from = message.parsed?.fromAddress,
                fromHeader = message.parsed?.fromHeader,
                toHeader = message.parsed?.toHeader,
                subject = message.parsed?.subject,
                textBody = message.parsed?.textBody,
                htmlBody = message.parsed?.htmlBody,
                headers =
                    if (message.parseStatus == ParseStatus.OK) {
                        message.parsed
                            ?.headers
                            .orEmpty()
                            .map { EmailHeaderDto(it.name, it.value) }
                    } else {
                        null
                    },
                links =
                    if (message.parseStatus == ParseStatus.OK) {
                        message.parsed
                            ?.links
                            .orEmpty()
                            .map { EmailLinkDto(it.href, it.text) }
                    } else {
                        null
                    },
                attachments =
                    message.attachments.map {
                        AttachmentMetaDto(it.id.value, it.fileName, it.contentType, it.sizeBytes)
                    },
                contentFingerprint = message.contentFingerprint,
                possibleDuplicateOfMessageId = message.possibleDuplicateOfMessageId?.value,
                rawSizeBytes = message.rawSizeBytes,
            )
    }
}

data class MessagePageDto(
    val items: List<MessageDto>,
    val nextCursor: String?,
)

data class HeaderMatcherDto(
    val name: String? = null,
    val value: String? = null,
)

data class MessageMatcherDto(
    val from: String? = null,
    val subjectContains: String? = null,
    val subjectEquals: String? = null,
    val headers: List<HeaderMatcherDto>? = null,
)

data class WaitRequestDto(
    val matcher: MessageMatcherDto? = null,
    val timeoutSeconds: Long? = null,
)

data class WaitResultDto(
    val status: String,
    val message: MessageDto?,
    val elapsedMs: Long,
    val arrivedButUnmatchedCount: Int?,
    val parseFailedCount: Int?,
)

/** Opaque cursor: base64url of `<epochMicros>:<messageId>`. */
object Cursors {
    fun encode(
        receivedAt: Instant,
        id: UUID,
    ): String {
        val micros = receivedAt.epochSecond * 1_000_000 + receivedAt.nano / 1_000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros:$id".toByteArray())
    }

    fun decode(cursor: String): MessageCursor? =
        runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(cursor))
            val (micros, id) = decoded.split(':', limit = 2)
            val microsLong = micros.toLong()
            MessageCursor(
                receivedAt = Instant.ofEpochSecond(microsLong / 1_000_000, (microsLong % 1_000_000) * 1_000),
                id = MessageId(UUID.fromString(id)),
            )
        }.getOrNull()
}
