package email.testinbox.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import email.testinbox.application.port.AppendOutcome
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.NOTIFICATION_CHANNEL
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Attachment
import email.testinbox.domain.message.EmailHeader
import email.testinbox.domain.message.EmailLink
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcMessageRepository(
    private val jdbc: JdbcClient,
) : MessageRepository {
    private val json: ObjectMapper = jacksonObjectMapper()

    /**
     * ADR-020 atomicity invariant: the message row (and attachment metadata)
     * insert and `pg_notify` execute in ONE transaction. PostgreSQL delivers
     * the notification only after — and only if — this transaction commits,
     * so a notified waiter always finds the message queryable as Visible.
     */
    @Transactional
    override fun appendVisible(message: Message): AppendOutcome {
        val inserted =
            jdbc
                .sql(
                    """
                    INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, provider_message_id,
                                         envelope_from, envelope_to, raw_object_key, raw_size_bytes,
                                         content_fingerprint, possible_duplicate_of_message_id,
                                         parse_status, parse_error, from_address, from_header, to_header,
                                         subject, text_body, html_body, headers, links)
                    VALUES (:id, :workspaceId, :inboxId, :receivedAt, :provider, :providerMessageId,
                            :envelopeFrom, :envelopeTo, :rawObjectKey, :rawSizeBytes,
                            :contentFingerprint, :possibleDuplicateOf,
                            :parseStatus, :parseError, :fromAddress, :fromHeader, :toHeader,
                            :subject, :textBody, :htmlBody, :headers::jsonb, :links::jsonb)
                    ON CONFLICT (provider, provider_message_id) WHERE provider_message_id IS NOT NULL DO NOTHING
                    """.trimIndent(),
                ).param("id", message.id.value)
                .param("workspaceId", message.workspaceId.value)
                .param("inboxId", message.inboxId.value)
                .param("receivedAt", Timestamps.toDb(message.receivedAt))
                .param("provider", message.provider)
                .param("providerMessageId", message.providerMessageId)
                .param("envelopeFrom", message.envelopeFrom)
                .param("envelopeTo", message.envelopeTo)
                .param("rawObjectKey", message.rawObjectKey)
                .param("rawSizeBytes", message.rawSizeBytes)
                .param("contentFingerprint", message.contentFingerprint)
                .param("possibleDuplicateOf", message.possibleDuplicateOfMessageId?.value)
                .param("parseStatus", message.parseStatus.name)
                .param("parseError", message.parseError)
                .param("fromAddress", message.parsed?.fromAddress)
                .param("fromHeader", message.parsed?.fromHeader)
                .param("toHeader", message.parsed?.toHeader)
                .param("subject", message.parsed?.subject)
                .param("textBody", message.parsed?.textBody)
                .param("htmlBody", message.parsed?.htmlBody)
                .param("headers", json.writeValueAsString(message.parsed?.headers ?: emptyList<EmailHeader>()))
                .param("links", json.writeValueAsString(message.parsed?.links ?: emptyList<EmailLink>()))
                .update()
        if (inserted == 0) return AppendOutcome.DuplicateProviderEvent

        for (attachment in message.attachments) {
            jdbc
                .sql(
                    """
                    INSERT INTO attachment (id, workspace_id, message_id, file_name, content_type, size_bytes, object_key)
                    VALUES (:id, :workspaceId, :messageId, :fileName, :contentType, :sizeBytes, :objectKey)
                    """.trimIndent(),
                ).param("id", attachment.id.value)
                .param("workspaceId", message.workspaceId.value)
                .param("messageId", message.id.value)
                .param("fileName", attachment.fileName)
                .param("contentType", attachment.contentType)
                .param("sizeBytes", attachment.sizeBytes)
                .param("objectKey", attachment.objectKey)
                .update()
        }

        // Same transaction as the insert — never notify-after-commit from here.
        jdbc
            .sql("SELECT pg_notify(:channel, :payload)")
            .param("channel", NOTIFICATION_CHANNEL)
            .param("payload", message.inboxId.value.toString())
            .query()
            .listOfRows()
        return AppendOutcome.Appended
    }

    override fun findEarliestIdByFingerprint(
        inboxId: InboxId,
        fingerprint: String,
    ): MessageId? =
        jdbc
            .sql(
                """
                SELECT id FROM message
                 WHERE inbox_id = :inboxId AND content_fingerprint = :fingerprint
                 ORDER BY received_at, id LIMIT 1
                """.trimIndent(),
            ).param("inboxId", inboxId.value)
            .param("fingerprint", fingerprint)
            .query { rs, _ -> MessageId(rs.getObject("id", UUID::class.java)) }
            .optional()
            .orElse(null)

    override fun listVisible(inboxId: InboxId): List<Message> {
        val messages =
            jdbc
                .sql("SELECT * FROM message WHERE inbox_id = :inboxId ORDER BY received_at, id")
                .param("inboxId", inboxId.value)
                .query { rs, _ -> mapMessage(rs) }
                .list()
        return withAttachments(messages)
    }

    override fun listPage(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        after: MessageCursor?,
        limit: Int,
    ): List<Message> {
        val messages =
            if (after == null) {
                jdbc
                    .sql(
                        """
                        SELECT * FROM message WHERE workspace_id = :workspaceId AND inbox_id = :inboxId
                         ORDER BY received_at, id LIMIT :limit
                        """.trimIndent(),
                    ).param("workspaceId", workspaceId.value)
                    .param("inboxId", inboxId.value)
                    .param("limit", limit)
                    .query { rs, _ -> mapMessage(rs) }
                    .list()
            } else {
                jdbc
                    .sql(
                        """
                        SELECT * FROM message WHERE workspace_id = :workspaceId AND inbox_id = :inboxId
                           AND (received_at, id) > (:afterReceivedAt, :afterId)
                         ORDER BY received_at, id LIMIT :limit
                        """.trimIndent(),
                    ).param("workspaceId", workspaceId.value)
                    .param("inboxId", inboxId.value)
                    .param("afterReceivedAt", Timestamps.toDb(after.receivedAt))
                    .param("afterId", after.id.value)
                    .param("limit", limit)
                    .query { rs, _ -> mapMessage(rs) }
                    .list()
            }
        return withAttachments(messages)
    }

    override fun findById(
        workspaceId: WorkspaceId,
        id: MessageId,
    ): Message? {
        val message =
            jdbc
                .sql("SELECT * FROM message WHERE id = :id AND workspace_id = :workspaceId")
                .param("id", id.value)
                .param("workspaceId", workspaceId.value)
                .query { rs, _ -> mapMessage(rs) }
                .optional()
                .orElse(null) ?: return null
        return withAttachments(listOf(message)).first()
    }

    override fun exists(id: MessageId): Boolean =
        jdbc
            .sql("SELECT 1 FROM message WHERE id = :id")
            .param("id", id.value)
            .query()
            .listOfRows()
            .isNotEmpty()

    private fun withAttachments(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages
        val byMessage =
            jdbc
                .sql("SELECT * FROM attachment WHERE message_id IN (:ids) ORDER BY id")
                .param("ids", messages.map { it.id.value })
                .query { rs, _ -> mapAttachment(rs) }
                .list()
                .groupBy { it.messageId }
        return messages.map { it.copy(attachments = byMessage[it.id] ?: emptyList()) }
    }

    private fun mapAttachment(rs: ResultSet): Attachment =
        Attachment(
            id = AttachmentId(rs.getObject("id", UUID::class.java)),
            messageId = MessageId(rs.getObject("message_id", UUID::class.java)),
            fileName = rs.getString("file_name"),
            contentType = rs.getString("content_type"),
            sizeBytes = rs.getLong("size_bytes"),
            objectKey = rs.getString("object_key"),
        )

    private fun mapMessage(rs: ResultSet): Message {
        val parseStatus = ParseStatus.valueOf(rs.getString("parse_status"))
        val parsed =
            if (parseStatus == ParseStatus.OK) {
                ParsedContent(
                    fromAddress = rs.getString("from_address"),
                    fromHeader = rs.getString("from_header"),
                    toHeader = rs.getString("to_header"),
                    subject = rs.getString("subject"),
                    textBody = rs.getString("text_body"),
                    htmlBody = rs.getString("html_body"),
                    headers = json.readValue<List<EmailHeader>>(rs.getString("headers")),
                    links = json.readValue<List<EmailLink>>(rs.getString("links")),
                )
            } else {
                null
            }
        return Message(
            id = MessageId(rs.getObject("id", UUID::class.java)),
            workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
            inboxId = InboxId(rs.getObject("inbox_id", UUID::class.java)),
            receivedAt = Timestamps.fromDb(rs, "received_at")!!,
            provider = rs.getString("provider"),
            providerMessageId = rs.getString("provider_message_id"),
            envelopeFrom = rs.getString("envelope_from"),
            envelopeTo = rs.getString("envelope_to"),
            rawObjectKey = rs.getString("raw_object_key"),
            rawSizeBytes = rs.getLong("raw_size_bytes"),
            contentFingerprint = rs.getString("content_fingerprint"),
            possibleDuplicateOfMessageId =
                rs.getObject("possible_duplicate_of_message_id", UUID::class.java)?.let(::MessageId),
            parseStatus = parseStatus,
            parseError = rs.getString("parse_error"),
            parsed = parsed,
            attachments = emptyList(),
        )
    }
}
