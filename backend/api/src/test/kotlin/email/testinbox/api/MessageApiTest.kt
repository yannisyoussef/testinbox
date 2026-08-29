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
        blobs.put(message.rawObjectKey, "From: a@b.c\r\n\r\nraw-bytes".toByteArray(), "message/rfc822")
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
        blobs.put(key, byteArrayOf(0x25, 0x50), "application/pdf")
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
}

private fun JsonNode.single(): JsonNode {
    check(this.isArray && this.size() == 1) { "expected single-element array, got $this" }
    return this[0]
}
