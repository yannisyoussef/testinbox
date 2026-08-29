package email.testinbox.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.domain.InboxId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InboxApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    private fun createInbox(body: String = """{}"""): JsonNode {
        val response = post("/v1/inboxes", body)
        response.statusCode.value() shouldBe 201
        return json.readTree(response.body)
    }

    @Test
    fun `requests without a valid key get an RFC 7807 401`() {
        for (response in listOf(
            get("/v1/inboxes/${UUID.randomUUID()}", key = null),
            get("/v1/inboxes/${UUID.randomUUID()}", key = "tk_wrong"),
        )) {
            response.statusCode.value() shouldBe 401
            response.headers.contentType.toString() shouldContain "application/problem+json"
            val problem = json.readTree(response.body)
            problem["type"].asText() shouldBe "https://testinbox.email/problems/unauthorized"
            problem["correlationId"].asText().isNotBlank() shouldBe true
        }
    }

    @Test
    fun `create inbox returns a generated address with experimental stability header`() {
        val response = post("/v1/inboxes", """{"aliasHint":"Signup Flow","ttlSeconds":600}""")
        response.statusCode.value() shouldBe 201
        response.headers.getFirst("X-API-Stability") shouldBe "experimental"
        response.headers.getFirst("X-Correlation-Id").shouldNotBeNull()
        val inbox = json.readTree(response.body)
        inbox["address"].asText() shouldEndWith "@testinbox.local"
        inbox["address"].asText() shouldContain "signup-flow-"
        inbox["addressMode"].asText() shouldBe "GENERATED"
        inbox["state"].asText() shouldBe "ACTIVE"
    }

    @Test
    fun `invalid create requests are 400 problems`() {
        post("/v1/inboxes", """{"ttlSeconds":-5}""").statusCode.value() shouldBe 400
        post("/v1/inboxes", """{"addressMode":"WEIRD"}""").statusCode.value() shouldBe 400
        post("/v1/inboxes", """{"addressMode":"EXACT"}""").statusCode.value() shouldBe 400
        post("/v1/inboxes", """{"addressMode":"EXACT","localPart":"postmaster"}""")
            .statusCode
            .value() shouldBe 400
        post("/v1/inboxes", """not json""").statusCode.value() shouldBe 400
    }

    @Test
    fun `exact mode reserves the address and a second reservation gets 409`() {
        val localPart = "api-exact-${UUID.randomUUID().toString().take(8)}"
        val first = post("/v1/inboxes", """{"addressMode":"EXACT","localPart":"$localPart"}""")
        first.statusCode.value() shouldBe 201
        json.readTree(first.body)["address"].asText() shouldBe "$localPart@testinbox.local"

        val second = post("/v1/inboxes", """{"addressMode":"EXACT","localPart":"$localPart"}""")
        second.statusCode.value() shouldBe 409
        val problem = json.readTree(second.body)
        problem["type"].asText() shouldBe "https://testinbox.email/problems/address-already-reserved"
    }

    @Test
    fun `concurrent exact reservations through the API - one 201, rest 409 (ADR-021)`() {
        val localPart = "api-race-${UUID.randomUUID().toString().take(8)}"
        val threads = 6
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit<Int> {
                        gate.await(5, TimeUnit.SECONDS)
                        post("/v1/inboxes", """{"addressMode":"EXACT","localPart":"$localPart"}""")
                            .statusCode
                            .value()
                    }
                }
            gate.countDown()
            val codes = futures.map { it.get(30, TimeUnit.SECONDS) }
            codes.count { it == 201 } shouldBe 1
            codes.count { it == 409 } shouldBe threads - 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `get inbox is workspace-scoped - cross-tenant returns 404, not 403`() {
        val inbox = createInbox()
        val id = inbox["id"].asText()
        get("/v1/inboxes/$id").statusCode.value() shouldBe 200
        val crossTenant = get("/v1/inboxes/$id", key = otherWorkspaceKey)
        crossTenant.statusCode.value() shouldBe 404
        json.readTree(crossTenant.body)["type"].asText() shouldBe
            "https://testinbox.email/problems/inbox-not-found"
    }

    @Test
    fun `missing scope yields 403 missing-scope problem`() {
        val response = post("/v1/inboxes", """{}""", key = readOnlyKey)
        response.statusCode.value() shouldBe 403
        json.readTree(response.body)["type"].asText() shouldBe
            "https://testinbox.email/problems/missing-scope"
    }

    @Test
    fun `delete inbox returns 204 and the inbox eventually disappears via the sweep`() {
        val inbox = createInbox()
        val id = inbox["id"].asText()
        delete("/v1/inboxes/$id").statusCode.value() shouldBe 204
        // Immediately after deletion the inbox is DELETED (still readable) or already swept (404).
        val after = get("/v1/inboxes/$id")
        check(after.statusCode.value() == 404 || json.readTree(after.body)["state"].asText() == "DELETED")
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted {
            get("/v1/inboxes/$id").statusCode.value() shouldBe 404
        }
    }

    @Test
    fun `message listing paginates with an opaque cursor`() {
        val inbox = createInbox()
        val id = InboxId(UUID.fromString(inbox["id"].asText()))
        val base =
            java.time.Instant
                .now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        repeat(3) { i ->
            appendVisibleMessage(id, inbox["address"].asText(), subject = "msg-$i", receivedAt = base.plusMillis(i.toLong()))
        }
        val firstPage = json.readTree(get("/v1/inboxes/$id/messages?limit=2").body)
        firstPage["items"].size() shouldBe 2
        val cursor = firstPage["nextCursor"].asText()
        val secondPage = json.readTree(get("/v1/inboxes/$id/messages?limit=2&cursor=$cursor").body)
        secondPage["items"].size() shouldBe 1
        secondPage["items"][0]["subject"].asText() shouldBe "msg-2"
        get("/v1/inboxes/$id/messages?cursor=garbage!!").statusCode.value() shouldBe 400
    }
}
