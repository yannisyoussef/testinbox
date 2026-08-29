package email.testinbox.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.domain.InboxId
import email.testinbox.domain.message.ParseStatus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

class WaitApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    private fun createInbox(): JsonNode = json.readTree(post("/v1/inboxes", """{}""").body)

    @Test
    fun `wait returns MATCHED immediately for an already-visible message`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        appendVisibleMessage(inboxId, inbox["address"].asText())
        val response =
            post(
                "/v1/inboxes/$inboxId/messages/wait",
                """{"matcher":{"subjectContains":"Verify"},"timeoutSeconds":5}""",
            )
        response.statusCode.value() shouldBe 200
        val result = json.readTree(response.body)
        result["status"].asText() shouldBe "MATCHED"
        result["message"]["subject"].asText() shouldBe "Verify your email"
    }

    @Test
    fun `wait resolves when a matching message becomes visible mid-wait`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                Thread.sleep(400)
                appendVisibleMessage(inboxId, inbox["address"].asText(), subject = "late arrival")
            }
            val start = System.nanoTime()
            val response =
                post(
                    "/v1/inboxes/$inboxId/messages/wait",
                    """{"matcher":{"subjectContains":"late"},"timeoutSeconds":5}""",
                )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val result = json.readTree(response.body)
            result["status"].asText() shouldBe "MATCHED"
            // Push-woken, not timeout-bound: resolves well before the 5s window.
            elapsedMs shouldBeLessThan 4_000
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `wait timeout carries unmatched and parse-failed diagnostics (ADR-020, 200 not 408)`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        // The diagnostics count messages that became visible DURING the window
        // (wait-semantics.md), so stamp arrivals just inside it.
        val inWindow =
            java.time.Instant
                .now()
                .plusMillis(200)
        appendVisibleMessage(inboxId, inbox["address"].asText(), subject = "unrelated", receivedAt = inWindow)
        appendVisibleMessage(
            inboxId,
            inbox["address"].asText(),
            parseStatus = ParseStatus.FAILED,
            receivedAt = inWindow,
        )
        val response =
            post(
                "/v1/inboxes/$inboxId/messages/wait",
                """{"matcher":{"subjectContains":"never-arrives"},"timeoutSeconds":1}""",
            )
        response.statusCode.value() shouldBe 200
        val result = json.readTree(response.body)
        result["status"].asText() shouldBe "TIMEOUT"
        result["arrivedButUnmatchedCount"].asInt() shouldBe 1
        result["parseFailedCount"].asInt() shouldBe 1
    }

    @Test
    fun `wait on a deleted inbox returns 410 inbox-gone`() {
        val inbox = createInbox()
        val id = inbox["id"].asText()
        delete("/v1/inboxes/$id").statusCode.value() shouldBe 204
        val response = post("/v1/inboxes/$id/messages/wait", """{"timeoutSeconds":1}""")
        // The sweep may hard-delete between the DELETE and the wait: 410 (still
        // present, non-active) or 404 (already swept) are both contract-correct.
        check(response.statusCode.value() in setOf(404, 410)) { "got ${response.statusCode}" }
        if (response.statusCode.value() == 410) {
            json.readTree(response.body)["type"].asText() shouldBe
                "https://testinbox.email/problems/inbox-gone"
        }
    }

    @Test
    fun `wait validation - missing or non-positive timeout is a 400 problem`() {
        val inbox = createInbox()
        val id = inbox["id"].asText()
        post("/v1/inboxes/$id/messages/wait", """{}""").statusCode.value() shouldBe 400
        post("/v1/inboxes/$id/messages/wait", """{"timeoutSeconds":0}""").statusCode.value() shouldBe 400
    }

    @Test
    fun `wait is non-consuming - two sequential waits see the same message`() {
        val inbox = createInbox()
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val message = appendVisibleMessage(inboxId, inbox["address"].asText())
        repeat(2) {
            val result =
                json.readTree(post("/v1/inboxes/$inboxId/messages/wait", """{"timeoutSeconds":2}""").body)
            result["status"].asText() shouldBe "MATCHED"
            result["message"]["id"].asText() shouldBe message.id.value.toString()
        }
    }
}
