package email.testinbox.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.application.port.BlobStore
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.message.Attachment
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class MessageApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var blobs: BlobStore

    @Autowired lateinit var jdbc: org.springframework.jdbc.core.simple.JdbcClient

    private fun createInbox(): JsonNode = json.readTree(post("/v1/inboxes", """{}""").body)

    @Test
    fun `get message returns parsed fields and is workspace-scoped`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val message = appendVisibleMessage(inboxId, inbox["address"].asText())
        val response = get("/v1/messages/${message.id}")
        response.statusCode.value() shouldBe 200
        val body = json.readTree(response.body)
        body["subject"].asText() shouldBe "Verify your email"
        body["from"].asText() shouldBe "no-reply@example.com"
        body["contentFingerprint"].asText() shouldBe message.contentFingerprint

        get("/v1/messages/${message.id}", key = otherWorkspaceKey).statusCode.value() shouldBe 404
        get("/v1/messages/${UUID.randomUUID()}").statusCode.value() shouldBe 404
    }

    @Test
    fun `raw endpoint streams the stored MIME with rfc822 content type`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val message = appendVisibleMessage(inboxId, inbox["address"].asText())
        seed(message.rawObjectKey, "From: a@b.c\r\n\r\nraw-bytes".toByteArray())
        val response = get("/v1/messages/${message.id}/raw")
        response.statusCode.value() shouldBe 200
        response.headers.contentType.toString() shouldContain "message/rfc822"
        response.headers.getFirst("X-Content-Type-Options") shouldBe "nosniff"
        response.body!! shouldContain "raw-bytes"
    }

    @Test
    fun `attachment endpoints - metadata list and hardened byte download`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        var message = appendVisibleMessage(inboxId, inbox["address"].asText())
        val attachmentId = AttachmentId(UUID.randomUUID())
        val key = "$bootstrapWorkspaceId/$inboxId/${message.id}/attachments/$attachmentId"
        seed(key, byteArrayOf(0x25, 0x50))
        // Register attachment metadata through the repository (same tx contract as ingestion).
        message =
            message.copy(
                id = email.testinbox.domain.MessageId(UUID.randomUUID()),
                contentFingerprint = UUID.randomUUID().toString(),
                attachments = emptyList(),
            )
        val withAttachment =
            message.copy(
                attachments =
                    listOf(
                        Attachment(
                            id = attachmentId,
                            messageId = message.id,
                            fileName = "../..-evil<script>.pdf",
                            contentType = "application/pdf",
                            sizeBytes = 2,
                            objectKey = key,
                        ),
                    ),
            )
        messages.appendVisible(withAttachment)

        val list = get("/v1/messages/${message.id}/attachments")
        list.statusCode.value() shouldBe 200
        val meta = json.readTree(list.body).single()
        meta["fileName"].asText() shouldBe "../..-evil<script>.pdf"

        val download = get("/v1/messages/${message.id}/attachments/$attachmentId")
        download.statusCode.value() shouldBe 200
        download.headers.getFirst("X-Content-Type-Options") shouldBe "nosniff"
        download.headers.getFirst("Content-Security-Policy")!! shouldContain "default-src 'none'"
        // Sender filename never reaches the disposition header unsanitized.
        val disposition = download.headers.getFirst("Content-Disposition")!!
        disposition shouldContain "attachment"
        disposition.contains("<script>") shouldBe false
        disposition.contains("..") shouldBe false

        get("/v1/messages/${message.id}/attachments/${UUID.randomUUID()}").statusCode.value() shouldBe 404
    }

    @Test
    fun `an expired inbox's message is 404 on every read endpoint while paced retention still holds its rows and blobs`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val message = appendVisibleMessage(inboxId, inbox["address"].asText())
        seed(message.rawObjectKey, "From: a@b.c\r\n\r\nraw-bytes".toByteArray())
        get("/v1/messages/${message.id}/raw").statusCode.value() shouldBe 200

        jdbc
            .sql("UPDATE inbox SET state = 'EXPIRED' WHERE id = ?")
            .param(inboxId.value)
            .update() shouldBe 1

        listOf("", "/raw", "/attachments", "/attachments/${UUID.randomUUID()}").forEach { suffix ->
            get("/v1/messages/${message.id}$suffix").statusCode.value() shouldBe 404
        }
        val list = get("/v1/inboxes/$inboxId/messages")
        list.statusCode.value() shouldBe 200
        json.readTree(list.body)["items"].size() shouldBe 0
        String(blobs.get(message.rawObjectKey)!!) shouldContain "raw-bytes" // still stored: only serving stops
    }

    /** Seeds an object through the only write path there is: a fenced upload (ADR-035 §5). */
    private fun seed(
        key: String,
        bytes: ByteArray,
    ) {
        blobs.putReserved(
            email.testinbox.application.port
                .ReservedUpload(key, bytes, java.time.Instant.now(), java.time.Duration.ofSeconds(120)),
        ) shouldBe email.testinbox.application.port.UploadOutcome.Stored
    }
}

private fun JsonNode.single(): JsonNode {
    check(this.isArray && this.size() == 1) { "expected single-element array, got $this" }
    return this[0]
}
