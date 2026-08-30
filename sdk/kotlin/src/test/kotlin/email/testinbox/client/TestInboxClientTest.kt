package email.testinbox.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SDK unit tests against a scripted JDK HttpServer stub — no external
 * dependencies, runs on any JDK >= 17 (ADR-023 consumer perspective).
 */
class TestInboxClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: TestInboxClient

    data class RecordedRequest(val method: String, val path: String, val auth: String?, val body: String)

    data class ScriptedResponse(val status: Int, val body: String, val delayMillis: Long = 0)

    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    private val responses = ConcurrentLinkedQueue<ScriptedResponse>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests +=
                RecordedRequest(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("Authorization"),
                    body,
                )
            val scripted = responses.poll() ?: ScriptedResponse(500, """{"title":"unscripted"}""")
            if (scripted.delayMillis > 0) Thread.sleep(scripted.delayMillis)
            val (status, payload) = scripted.status to scripted.body
            val bytes = payload.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        client =
            TestInboxClient(
                apiKey = "tk_unit",
                baseUrl = "http://localhost:${server.address.port}",
                serverWaitCap = Duration.ofSeconds(60),
            )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun script(status: Int, body: String, delayMillis: Long = 0) {
        responses += ScriptedResponse(status, body, delayMillis)
    }

    private val inboxJson =
        """
        {"id":"11111111-1111-1111-1111-111111111111","address":"a-token@testinbox.local",
         "addressMode":"GENERATED","state":"ACTIVE","createdAt":"2026-08-29T12:00:00Z",
         "expiresAt":"2026-08-29T12:15:00Z","someFutureField":{"nested":true}}
        """.trimIndent()

    private val messageJson =
        """
        {"id":"22222222-2222-2222-2222-222222222222","inboxId":"11111111-1111-1111-1111-111111111111",
         "receivedAt":"2026-08-29T12:01:00Z","envelopeTo":"a-token@testinbox.local","parseStatus":"OK",
         "from":"no-reply@example.com","subject":"Verify your email","textBody":"click the link",
         "links":[{"href":"https://x.test/verify","text":"Verify"}],
         "headers":[{"name":"X-Run","value":"1"}],"attachments":[],
         "contentFingerprint":"f","rawSizeBytes":10,"unknownEnumCarrier":"FUTURE_VALUE"}
        """.trimIndent()

    @Test
    fun `createInbox sends the request and parses the inbox, tolerating unknown fields`() {
        script(201, inboxJson)
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(10), aliasHint = "signup"))
        assertEquals("a-token@testinbox.local", inbox.address)
        assertEquals("ACTIVE", inbox.state)
        val request = requests.poll()!!
        assertEquals("POST", request.method)
        assertEquals("/v1/inboxes", request.path)
        assertEquals("Bearer tk_unit", request.auth)
        assertTrue(request.body.contains("\"ttlSeconds\":600"))
        assertTrue(request.body.contains("\"aliasHint\":\"signup\""))
    }

    @Test
    fun `awaitMessage chains a server TIMEOUT into a second poll and returns the match`() {
        script(201, inboxJson)
        script(200, """{"status":"TIMEOUT","elapsedMs":60000,"arrivedButUnmatchedCount":0,"parseFailedCount":0}""")
        script(200, """{"status":"MATCHED","elapsedMs":150,"message":$messageJson}""")
        val inbox = client.createInboxBlocking()
        val message =
            inbox.awaitMessageBlocking(
                Duration.ofSeconds(120),
                MessageMatcher.builder().subjectContains("Verify").build(),
            )
        assertEquals("Verify your email", message.subject)
        assertEquals("https://x.test/verify", message.links.single().href)
        requests.poll() // create
        val firstWait = requests.poll()!!
        val secondWait = requests.poll()!!
        assertEquals("/v1/inboxes/${inbox.id}/messages/wait", firstWait.path)
        // 120s budget with a 60s server cap: first window is 60s.
        assertTrue(firstWait.body.contains("\"timeoutSeconds\":60"))
        assertTrue(secondWait.body.contains("subjectContains"))
    }

    @Test
    fun `overall timeout raises a typed exception carrying the last poll diagnostics`() {
        script(201, inboxJson)
        // The real server holds the connection for the wait window; emulate by
        // delaying the TIMEOUT reply past the caller's 1s overall budget.
        script(
            200,
            """{"status":"TIMEOUT","elapsedMs":1000,"arrivedButUnmatchedCount":3,"parseFailedCount":1}""",
            delayMillis = 1200,
        )
        val inbox = client.createInboxBlocking()
        val exception =
            assertThrows(TestInboxTimeoutException::class.java) {
                inbox.awaitMessageBlocking(Duration.ofSeconds(1))
            }
        assertEquals(3, exception.arrivedButUnmatchedCount)
        assertEquals(1, exception.parseFailedCount)
        assertTrue(exception.elapsedMs >= 0)
    }

    @Test
    fun `unknown wait status keeps chaining instead of throwing (forward compatibility)`() {
        script(201, inboxJson)
        script(200, """{"status":"SOME_FUTURE_STATUS","elapsedMs":10}""")
        script(200, """{"status":"MATCHED","elapsedMs":20,"message":$messageJson}""")
        val inbox = client.createInboxBlocking()
        val message = inbox.awaitMessageBlocking(Duration.ofSeconds(30))
        assertEquals("Verify your email", message.subject)
    }

    @Test
    fun `RFC 7807 responses map to the typed exception hierarchy`() {
        script(401, """{"type":"https://testinbox.email/problems/unauthorized","title":"Unauthorized","status":401,"correlationId":"c-1"}""")
        val auth = assertThrows(TestInboxAuthException::class.java) { client.getInboxBlocking("x") }
        assertEquals("c-1", auth.correlationId)

        script(404, """{"type":"https://testinbox.email/problems/inbox-not-found","title":"Inbox not found","status":404}""")
        assertThrows(TestInboxNotFoundException::class.java) { client.getInboxBlocking("x") }

        script(409, """{"type":"https://testinbox.email/problems/address-already-reserved","title":"Conflict","status":409,"retryAfterSeconds":3600}""")
        val conflict =
            assertThrows(TestInboxConflictException::class.java) {
                client.createInboxBlocking(
                    CreateInboxOptions(addressMode = AddressMode.EXACT, localPart = "qa"),
                )
            }
        assertEquals(3600L, conflict.retryAfterSeconds)
    }

    @Test
    fun `410 during a wait surfaces as TestInboxInboxGoneException`() {
        script(201, inboxJson)
        script(410, """{"type":"https://testinbox.email/problems/inbox-gone","title":"Gone","status":410}""")
        val inbox = client.createInboxBlocking()
        assertThrows(TestInboxInboxGoneException::class.java) {
            inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        }
    }

    @Test
    fun `messages listing and delete round-trip`() {
        script(201, inboxJson)
        script(200, """{"items":[$messageJson],"nextCursor":null}""")
        script(204, "")
        val inbox = client.createInboxBlocking()
        val messages = inbox.messagesBlocking()
        assertEquals(1, messages.size)
        assertNull(messages[0].htmlBody)
        inbox.deleteBlocking()
        requests.poll()
        requests.poll()
        val deleteRequest = requests.poll()!!
        assertEquals("DELETE", deleteRequest.method)
        assertEquals("/v1/inboxes/${inbox.id}", deleteRequest.path)
    }

    @Test
    fun `kotlin coroutine surface works without the blocking facade`() {
        script(201, inboxJson)
        script(200, """{"status":"MATCHED","elapsedMs":5,"message":$messageJson}""")
        runBlocking {
            val inbox = client.createInbox()
            val message = inbox.awaitMessage(Duration.ofSeconds(10)) { subjectContains("Verify") }
            assertEquals("no-reply@example.com", message.from)
        }
    }
}
