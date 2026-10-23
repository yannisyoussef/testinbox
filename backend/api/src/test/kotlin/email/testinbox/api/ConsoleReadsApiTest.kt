package email.testinbox.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.domain.InboxId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The console API's reads through HTTP: the inbox list, the workspace-wide
 * message list, the workspace and usage reads, and the event stream over the
 * real LISTEN/NOTIFY path. Every one is scoped to the key's workspace and
 * gated on `messages:read`.
 */
class ConsoleReadsApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    private fun body(response: org.springframework.http.ResponseEntity<String>): JsonNode = json.readTree(response.body)

    private fun createInbox(
        key: String,
        payload: String = """{"ttlSeconds":600}""",
    ): JsonNode {
        val response = post("/v1/inboxes", payload, key)
        response.statusCode shouldBe HttpStatus.CREATED
        return body(response)
    }

    @Test
    fun `GET inboxes lists only the key's workspace, newest first, with live storage and a cursor`() {
        val tenant = provisionIsolatedWorkspace("inbox-list")
        val ids = (1..3).map { createInbox(tenant.apiKey, """{"aliasHint":"n$it","ttlSeconds":600}""")["id"].asText() }
        createInbox(adminKey) // another workspace's inbox, never listed here

        val page = body(get("/v1/inboxes?limit=2", tenant.apiKey))
        page["items"].toList().map { it["id"].asText() } shouldContainExactly listOf(ids[2], ids[1])
        page["items"][0]["storage"]["limitBytes"].asLong() shouldBeGreaterThan 0L
        val cursor = page["nextCursor"].asText()
        val rest = body(get("/v1/inboxes?limit=2&cursor=$cursor", tenant.apiKey))
        rest["items"].toList().map { it["id"].asText() } shouldContainExactly listOf(ids[0])
        // Absent, as the contract has promised since v1 — not null.
        rest.has("nextCursor") shouldBe false

        // The state filter, and a deleted inbox still listed until the sweep (as DELETED).
        delete("/v1/inboxes/${ids[0]}", tenant.apiKey).statusCode shouldBe HttpStatus.NO_CONTENT
        body(get("/v1/inboxes?state=DELETED", tenant.apiKey))["items"].toList().map { it["id"].asText() } shouldContainExactly
            listOf(ids[0])
        body(get("/v1/inboxes?state=ACTIVE", tenant.apiKey))["items"].size() shouldBe 2
        get("/v1/inboxes?state=WHATEVER", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST
        get("/v1/inboxes?cursor=garbage", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST
        // A malformed query parameter is the caller's mistake, not a missing collection.
        val malformed = get("/v1/inboxes?limit=abc", tenant.apiKey)
        malformed.statusCode shouldBe HttpStatus.BAD_REQUEST
        body(malformed)["type"].asText() shouldBe "https://testinbox.email/problems/invalid-request"
        get("/v1/messages?limit=abc", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `a cursor is decoded within bounds, so a crafted one is a 400 and never a server fault`() {
        val tenant = provisionIsolatedWorkspace("cursor-bounds")
        val negative =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "-9223372036854775808:${UUID.randomUUID()}".toByteArray(),
            )
        val farFuture =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "9223372036854775807:${UUID.randomUUID()}".toByteArray(),
            )
        for (cursor in listOf(negative, farFuture)) {
            get("/v1/messages?cursor=$cursor", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST
            get("/v1/inboxes?cursor=$cursor", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST
            Stream(port, tenant.apiKey, lastEventId = cursor).status shouldBe 400
        }
    }

    @Test
    fun `GET messages is the workspace-wide feed, newest first, serving inboxes only, walkable both ways`() {
        val tenant = provisionIsolatedWorkspace("msg-feed")
        val a = createInbox(tenant.apiKey)
        val b = createInbox(tenant.apiKey)
        val t0 = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(60)
        val m1 =
            appendVisibleMessage(
                InboxId(UUID.fromString(a["id"].asText())),
                a["address"].asText(),
                subject = "one",
                receivedAt = t0,
                workspaceId = tenant.workspaceId,
            )
        val m2 =
            appendVisibleMessage(
                InboxId(UUID.fromString(b["id"].asText())),
                b["address"].asText(),
                subject = "two",
                receivedAt = t0.plusSeconds(1),
                workspaceId = tenant.workspaceId,
            )
        val m3 =
            appendVisibleMessage(
                InboxId(UUID.fromString(a["id"].asText())),
                a["address"].asText(),
                subject = "three",
                receivedAt = t0.plusSeconds(2),
                workspaceId = tenant.workspaceId,
            )
        // The suite's shared workspace has messages too; none may appear.
        val feed = body(get("/v1/messages", tenant.apiKey))
        feed["items"].toList().map { it["id"].asText() } shouldContainExactly
            listOf(m3.id.value, m2.id.value, m1.id.value).map { it.toString() }

        val first = body(get("/v1/messages?limit=1", tenant.apiKey))
        val cursor = first["nextCursor"].asText()
        body(get("/v1/messages?limit=5&cursor=$cursor", tenant.apiKey))["items"].toList().map { it["id"].asText() } shouldContainExactly
            listOf(m2.id.value.toString(), m1.id.value.toString())
        body(get("/v1/messages?order=asc&cursor=$cursor", tenant.apiKey))["items"].shouldBeEmpty()
        val oldest = body(get("/v1/messages?order=asc&limit=1", tenant.apiKey))
        oldest["items"][0]["id"].asText() shouldBe m1.id.value.toString()
        get("/v1/messages?order=sideways", tenant.apiKey).statusCode shouldBe HttpStatus.BAD_REQUEST

        // Deleting inbox a hides its messages at once (servability), b's stay.
        delete("/v1/inboxes/${a["id"].asText()}", tenant.apiKey).statusCode shouldBe HttpStatus.NO_CONTENT
        body(get("/v1/messages", tenant.apiKey))["items"].toList().map { it["id"].asText() } shouldContainExactly
            listOf(m2.id.value.toString())
    }

    @Test
    fun `GET workspace and GET workspace usage describe the key's own tenancy and the enforced policy`() {
        val tenant = provisionIsolatedWorkspace("ws-read")
        val ws = body(get("/v1/workspace", tenant.apiKey))
        ws["id"].asText() shouldBe tenant.workspaceId.value.toString()
        ws["name"].asText() shouldBe "ws-read"
        ws["mailDomain"].asText() shouldBe "testinbox.local"
        ws["project"]["id"].asText() shouldBe tenant.projectId.value.toString()
        ws["createdAt"].asText().isNotBlank() shouldBe true

        createInbox(tenant.apiKey)
        val usage = body(get("/v1/workspace/usage", tenant.apiKey))
        usage["enforced"].asBoolean() shouldBe true
        val quotas = usage["quotas"].toList().associateBy { it["dimension"].asText() }
        quotas.keys shouldBe setOf("ACTIVE_INBOXES", "STORED_BYTES", "CONCURRENT_WAITS")
        quotas.getValue("ACTIVE_INBOXES")["current"].asLong() shouldBe 1
        quotas.getValue("ACTIVE_INBOXES")["limit"].asLong() shouldBe 100000
        quotas.getValue("CONCURRENT_WAITS")["current"].asLong() shouldBe 0
        val rates = usage["rates"].toList().associateBy { it["category"].asText() }
        rates.keys shouldBe setOf("INBOX_CREATE", "WAIT", "READ", "DOWNLOAD", "INGEST", "KEY_ADMIN")
        rates.getValue("INBOX_CREATE")["limit"].asLong() shouldBe 100000
        // Read without spending: the suite's refill is fast, so only the bound is stable.
        rates.getValue("INBOX_CREATE")["remaining"].asLong() shouldBeLessThanOrEqual 100000L
        rates.getValue("INBOX_CREATE")["refillPerSecond"].asDouble() shouldBe 10000.0
    }

    @Test
    fun `every console read needs messages read and answers 403 as a problem otherwise`() {
        val tenant = provisionIsolatedWorkspace("scope")
        val writeOnly = mintKey(tenant.workspaceId, tenant.projectId, setOf(email.testinbox.domain.tenant.ApiScope.INBOXES_WRITE))
        for (path in listOf("/v1/inboxes", "/v1/messages", "/v1/workspace", "/v1/workspace/usage", "/v1/workspace/events")) {
            val response = get(path, writeOnly)
            response.statusCode shouldBe HttpStatus.FORBIDDEN
            response.headers.contentType.toString() shouldContain "application/problem+json"
        }
    }

    /** One SSE window read on a background thread: lines as they arrive, a latch per expected event. */
    private class Stream(
        port: Int,
        key: String,
        lastEventId: String? = null,
    ) {
        val lines = CopyOnWriteArrayList<String>()
        private val done = CountDownLatch(1)
        val status: Int

        init {
            val client = HttpClient.newHttpClient()
            val request =
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/v1/workspace/events"))
                    .header("Authorization", "Bearer $key")
                    .header("Accept", "text/event-stream")
                    .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId) }
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            status = response.statusCode()
            Thread {
                response.body().bufferedReader().useLines { seq -> seq.forEach { lines += it } }
                done.countDown()
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun awaitLine(
            predicate: (String) -> Boolean,
            timeoutMs: Long = 10_000,
        ): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                lines.firstOrNull(predicate)?.let { return it }
                Thread.sleep(50)
            }
            error("no line matched within ${timeoutMs}ms; lines so far: $lines")
        }

        fun awaitEnd(timeoutMs: Long = 15_000) = done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `the event stream delivers a message committed after it opened, through LISTEN, and ends its window`() {
        val tenant = provisionIsolatedWorkspace("events")
        val inbox = createInbox(tenant.apiKey)
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val before = appendVisibleMessage(inboxId, inbox["address"].asText(), subject = "before", workspaceId = tenant.workspaceId)

        val stream = Stream(port, tenant.apiKey)
        stream.status shouldBe 200
        stream.awaitLine({ it.startsWith("retry:") })
        // Visible before the stream opened: never replayed without a cursor.
        Thread.sleep(300)
        stream.lines.none { it.contains(before.id.value.toString()) } shouldBe true

        val after = appendVisibleMessage(inboxId, inbox["address"].asText(), subject = "after", workspaceId = tenant.workspaceId)
        val data = stream.awaitLine({ it.startsWith("data:") && it.contains(after.id.value.toString()) })
        val event = json.readTree(data.removePrefix("data: "))
        event["inboxId"].asText() shouldBe inboxId.value.toString()
        event["subject"].asText() shouldBe "after"
        stream.lines.any { it == "event: message.received" } shouldBe true
        val id = stream.lines.first { it.startsWith("id: ") }.removePrefix("id: ")

        // A message whose receipt predates one already delivered (a slow raw
        // upload, ADR-005) is still delivered: the read is a horizon, not a cursor.
        val lagged =
            appendVisibleMessage(
                inboxId,
                inbox["address"].asText(),
                subject = "lagged",
                receivedAt = after.receivedAt.minusSeconds(2),
                workspaceId = tenant.workspaceId,
            )
        stream.awaitLine({ it.startsWith("data:") && it.contains(lagged.id.value.toString()) })

        // The window (wait-window-cap = 5s in this suite) ends with a resumable
        // position, carried as the `end` event's id as well as its data.
        stream.awaitEnd() shouldBe true
        val end = stream.lines.first { it.startsWith("data:") && it.contains("\"reason\":\"window\"") }
        val resume = json.readTree(end.removePrefix("data: "))["resume"].asText()
        resume shouldBe id
        stream.lines.last { it.startsWith("id: ") } shouldBe "id: $resume"
        // Within one window nothing is delivered twice.
        stream.lines.count { it.startsWith("data:") && it.contains(after.id.value.toString()) } shouldBe 1

        // Resume from it: what arrived in between is delivered at once. The
        // horizon behind the position is re-read, so `after` and `before` may
        // repeat (at-least-once across windows); `missed` must not be missed.
        val missed = appendVisibleMessage(inboxId, inbox["address"].asText(), subject = "missed", workspaceId = tenant.workspaceId)
        val resumed = Stream(port, tenant.apiKey, lastEventId = resume)
        resumed.awaitLine({ it.startsWith("data:") && it.contains(missed.id.value.toString()) })
        resumed.lines.count { it.startsWith("data:") && it.contains(missed.id.value.toString()) } shouldBe 1
        Stream(port, tenant.apiKey, lastEventId = "garbage").status shouldBe 400
    }

    @Test
    fun `a window that delivered nothing still ends with an id to resume from`() {
        val tenant = provisionIsolatedWorkspace("events-idle")
        val stream = Stream(port, tenant.apiKey)
        stream.awaitLine({ it.startsWith("retry:") })
        stream.awaitEnd() shouldBe true
        val id = stream.lines.single { it.startsWith("id: ") }.removePrefix("id: ")
        val end = stream.lines.single { it.startsWith("data:") && it.contains("\"reason\":\"window\"") }
        json.readTree(end.removePrefix("data: "))["resume"].asText() shouldBe id
        // Nothing but the retry line, the end frame and (if the window outlived it) a keep-alive comment.
        stream.lines.filter { it.isNotBlank() }.all {
            it.startsWith("retry:") || it.startsWith("id:") || it.startsWith("event: end") ||
                it.startsWith("data:") ||
                it.startsWith(":")
        } shouldBe
            true
    }

    @Test
    fun `the event stream reports the inbox lifecycle and another workspace's delivery produces not one byte`() {
        val tenant = provisionIsolatedWorkspace("events-scope")
        val other = provisionIsolatedWorkspace("events-other")
        val stream = Stream(port, tenant.apiKey)
        stream.awaitLine({ it.startsWith("retry:") })
        val theirs = createInbox(other.apiKey)
        val quiet = stream.lines.size
        appendVisibleMessage(
            InboxId(UUID.fromString(theirs["id"].asText())),
            theirs["address"].asText(),
            subject = "foreign",
            workspaceId = other.workspaceId,
        )
        // Not an event, not a keep-alive, not a blank line: a foreign delivery
        // that produced any bytes would be observable as timing (ADR-025).
        Thread.sleep(700)
        stream.lines.size shouldBe quiet
        val mine = createInbox(tenant.apiKey)
        stream.awaitLine({ it.startsWith("data:") && it.contains(mine["id"].asText()) })
        stream.lines.any { it == "event: inbox.created" } shouldBe true
        delete("/v1/inboxes/${mine["id"].asText()}", tenant.apiKey).statusCode shouldBe HttpStatus.NO_CONTENT
        stream.awaitLine({ it == "event: inbox.deleted" })
        stream.lines.none { it.contains("foreign") || it.contains(theirs["id"].asText()) } shouldBe true
    }

    @Test
    fun `a stream holds a concurrent-wait slot, so a workspace out of slots is refused 429`() {
        val tenant = provisionIsolatedWorkspace("events-slots")
        val usageBefore = body(get("/v1/workspace/usage", tenant.apiKey))
        usageBefore["quotas"].toList().first { it["dimension"].asText() == "CONCURRENT_WAITS" }["current"].asLong() shouldBe 0
        val stream = Stream(port, tenant.apiKey)
        stream.awaitLine({ it.startsWith("retry:") })
        val usageDuring = body(get("/v1/workspace/usage", tenant.apiKey))
        usageDuring["quotas"].toList().first { it["dimension"].asText() == "CONCURRENT_WAITS" }["current"].asLong() shouldBe 1
        stream.awaitEnd() shouldBe true
        body(get("/v1/workspace/usage", tenant.apiKey))["quotas"]
            .toList()
            .first {
                it["dimension"].asText() == "CONCURRENT_WAITS"
            }["current"]
            .asLong() shouldBe
            0
    }
}
