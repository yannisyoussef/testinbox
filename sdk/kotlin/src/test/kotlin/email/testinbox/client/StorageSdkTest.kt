package email.testinbox.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * ADR-035 §13 SDK semantics on the JVM (§17 test 45, TI-STORAGE-005):
 * storage snapshots, the per-Inbox atomic observation cursor, refusal-aware
 * chaining, the typed storage-limit exception, and compatibility with a
 * server that predates storage visibility. The stub server can hold a
 * response until the test releases it, so concurrent completion order is
 * decided by the test, never by timing.
 */
class StorageSdkTest {
    private lateinit var server: HttpServer
    private lateinit var client: TestInboxClient

    private data class Recorded(val method: String, val path: String, val body: String)

    /** A scripted response; [gate], when set, is awaited before it is written, and [delayMillis] models a server window. */
    private data class Scripted(val status: Int, val body: String, val gate: CountDownLatch? = null, val delayMillis: Long = 0)

    private val requests = ConcurrentLinkedQueue<Recorded>()
    private val responses = ConcurrentLinkedQueue<Scripted>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange: HttpExchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += Recorded(exchange.requestMethod, exchange.requestURI.path, body)
            val scripted = responses.poll() ?: Scripted(500, """{"title":"unscripted"}""")
            // A gate that is never released is a harness bug: fail loudly, never serve the answer late.
            scripted.gate?.let { check(it.await(10, TimeUnit.SECONDS)) { "a scripted gate was never released" } }
            if (scripted.delayMillis > 0) Thread.sleep(scripted.delayMillis)
            val bytes = scripted.body.toByteArray()
            exchange.responseHeaders.set("Content-Type", if (scripted.status >= 400) "application/problem+json" else "application/json")
            exchange.sendResponseHeaders(scripted.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        client = TestInboxClient("tk_unit", "http://localhost:${server.address.port}", Duration.ofSeconds(60))
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun script(status: Int, body: String, gate: CountDownLatch? = null, delayMillis: Long = 0) {
        responses += Scripted(status, body, gate, delayMillis)
    }

    private fun waitBodies(): List<String> = requests.filter { it.path.endsWith("/messages/wait") }.map { it.body }

    private val usage =
        """{"limitBytes":536870912,"storedBytes":402653184,"reservedBytes":15728640,"availableBytes":118489088,"overLimit":false}"""

    private fun inboxJson(
        count: Long? = 0,
        lastAt: String? = null,
        reason: String? = null,
        storage: String? = usage,
    ): String {
        val members =
            listOfNotNull(
                storage?.let { "\"storage\":$it" },
                count?.let { "\"storageRefusalCount\":$it" },
                "\"lastStorageRefusalAt\":${lastAt?.let { "\"$it\"" } ?: "null"}",
                "\"lastStorageRefusalReason\":${reason?.let { "\"$it\"" } ?: "null"}",
            ).joinToString(",")
        return """{"id":"11111111-1111-1111-1111-111111111111","address":"a@testinbox.local","addressMode":"GENERATED",
                   "state":"ACTIVE","createdAt":"2026-10-07T12:00:00Z","expiresAt":"2026-10-07T12:15:00Z",$members}"""
    }

    /** A representation from a server that predates TI-STORAGE-004: none of the four members. */
    private val legacyInboxJson =
        """{"id":"11111111-1111-1111-1111-111111111111","address":"a@testinbox.local","addressMode":"GENERATED",
           "state":"ACTIVE","createdAt":"2026-10-07T12:00:00Z","expiresAt":"2026-10-07T12:15:00Z"}"""

    private fun matched(count: Long? = null) =
        """{"status":"MATCHED","elapsedMs":5,"message":{"id":"22222222-2222-2222-2222-222222222222",
           "inboxId":"11111111-1111-1111-1111-111111111111","parseStatus":"OK","subject":"hi"}""" +
            (count?.let { ""","storageRefusalCount":$it,"lastStorageRefusalAt":"2026-10-07T12:00:30Z"""" } ?: "") + "}"

    private fun timeout(count: Long? = null) =
        """{"status":"TIMEOUT","elapsedMs":1000,"arrivedButUnmatchedCount":0,"parseFailedCount":0""" +
            (count?.let { ""","storageRefusalCount":$it,"lastStorageRefusalAt":null""" } ?: "") + "}"

    private fun refused(
        reason: String = "INBOX_LIMIT",
        boundary: Long = 0,
        count: Long = 1,
        lastAt: String? = "2026-10-07T12:00:30Z",
        scope: String? = ""","quota":"STORED_BYTES","limit":536870912,"current":536870000""",
    ) = """{"type":"https://testinbox.email/problems/storage-limit-exceeded","title":"Storage limit exceeded","status":409,
            "detail":"A storage ceiling ($reason) refused a copy","correlationId":"corr-409",
            "inboxId":"11111111-1111-1111-1111-111111111111","refusalReason":"$reason",
            "afterStorageRefusalCount":$boundary,"storageRefusalCount":$count""" +
        (lastAt?.let { ""","lastStorageRefusalAt":"$it"""" } ?: "") + (scope ?: "") + "}"

    private fun boundarySent(body: String): Long? =
        Regex(""""afterStorageRefusalCount":(\d+)""").find(body)?.groupValues?.get(1)?.toLong()

    // --- snapshots -----------------------------------------------------------------------------

    @Test
    fun `the inbox representation carries storage and refusal snapshots, mapped field by field`() {
        script(200, inboxJson(count = 2, lastAt = "2026-10-07T11:59:00Z", reason = "WORKSPACE_LIMIT"))
        val inbox = client.getInboxBlocking("11111111-1111-1111-1111-111111111111")
        assertEquals(StorageUsage(536870912, 402653184, 15728640, 118489088, false), inbox.storage)
        assertEquals(2L, inbox.storageRefusalCount)
        assertEquals(Instant.parse("2026-10-07T11:59:00Z"), inbox.lastStorageRefusalAt)
        assertEquals(StorageRefusalReason.WORKSPACE_LIMIT, inbox.lastStorageRefusalReason)
        assertEquals(2L, inbox.storageRefusalCursor)
    }

    @Test
    fun `an unknown future refusal reason round-trips as its exact wire value`() {
        script(200, inboxJson(count = 1, lastAt = "2026-10-07T11:59:00Z", reason = "SOME_FUTURE_REASON"))
        val inbox = client.getInboxBlocking("x")
        assertEquals(StorageRefusalReason("SOME_FUTURE_REASON"), inbox.lastStorageRefusalReason)
        assertEquals("SOME_FUTURE_REASON", inbox.lastStorageRefusalReason!!.wire)
    }

    @Test
    fun `an older server that omits every storage member yields null snapshots and no cursor, and still works`() {
        script(200, legacyInboxJson)
        val inbox = client.getInboxBlocking("x")
        assertEquals("a@testinbox.local", inbox.address)
        assertNull(inbox.storage)
        assertNull(inbox.storageRefusalCount)
        assertNull(inbox.lastStorageRefusalAt)
        assertNull(inbox.lastStorageRefusalReason)
        assertNull(inbox.storageRefusalCursor)
    }

    @Test
    fun `a present but malformed storage member is a protocol error, never a partial object`() {
        script(200, inboxJson(storage = """{"limitBytes":1,"storedBytes":2}"""))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
        script(200, inboxJson(count = -1))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
        script(200, inboxJson(count = 1, lastAt = "yesterday", reason = "INBOX_LIMIT"))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
    }

    // --- workspace storage ----------------------------------------------------------------------

    @Test
    fun `getWorkspaceStorage GETs the workspace endpoint and maps exactly the five members`() {
        script(200, """{"limitBytes":2147483648,"storedBytes":100,"reservedBytes":20,"availableBytes":2147483528,"overLimit":false,"globalLimitBytes":1}""")
        val storage = client.getWorkspaceStorageBlocking()
        val request = requests.poll()!!
        assertEquals("GET", request.method)
        assertEquals("/v1/workspace/storage", request.path)
        assertEquals(StorageUsage(2147483648, 100, 20, 2147483528, false), storage)
        assertEquals(2147483648, storage.limitBytes)
        assertFalse(storage.toString().contains("global"))
        // Kotlin coroutine surface too.
        script(200, usage)
        assertEquals(536870912, runBlocking { client.getWorkspaceStorage() }.limitBytes)
    }

    @Test
    fun `a 200 that omits one of the five members is a protocol error, never a default of zero`() {
        script(200, """{"limitBytes":1,"storedBytes":2,"reservedBytes":3,"overLimit":false}""")
        assertThrows(TestInboxProtocolException::class.java) { client.getWorkspaceStorageBlocking() }
    }

    // --- the cursor and the wait request ---------------------------------------------------------

    @Test
    fun `the default wait sends the cursor seeded from the representation, with no caller ceremony`() {
        script(200, inboxJson(count = 7, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        script(200, matched(7))
        client.getInboxBlocking("x").awaitMessageBlocking(Duration.ofSeconds(5))
        assertEquals(7L, boundarySent(waitBodies().single()))
    }

    @Test
    fun `a freshly created inbox sends 0 by default`() {
        script(201, inboxJson(count = 0))
        script(200, matched(0))
        client.createInboxBlocking().awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.builder().subjectContains("hi").build())
        assertEquals(0L, boundarySent(waitBodies().single()))
    }

    @Test
    fun `an older-server representation sends NO boundary by default - absence is not zero`() {
        script(200, legacyInboxJson)
        script(200, matched())
        val inbox = client.getInboxBlocking("x")
        inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        assertFalse(waitBodies().single().contains("afterStorageRefusalCount"))
        assertNull(inbox.storageRefusalCursor)
    }

    @Test
    fun `an explicit boundary on an older-server representation is sent and seeds the cursor`() {
        script(200, legacyInboxJson)
        script(200, matched())
        val inbox = client.getInboxBlocking("x")
        inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, 4L)
        assertEquals(4L, boundarySent(waitBodies().single()))
        assertEquals(4L, inbox.storageRefusalCursor)
    }

    @Test
    fun `explicit above the cursor advances it and is sent, explicit below is overridden by the cursor`() {
        script(200, inboxJson(count = 2, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        script(200, matched(2))
        script(200, matched(2))
        val inbox = client.getInboxBlocking("x")
        inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, 5L)
        assertEquals(5L, boundarySent(waitBodies()[0]))
        assertEquals(5L, inbox.storageRefusalCursor)
        inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, 2L)
        assertEquals(5L, boundarySent(waitBodies()[1]))
        assertEquals(5L, inbox.storageRefusalCursor)
    }

    @Test
    fun `a negative boundary and the contradictory opt-out combination are refused locally with zero HTTP calls`() {
        script(201, inboxJson())
        val inbox = client.createInboxBlocking()
        requests.clear()
        assertThrows(IllegalArgumentException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, -1L) }
        assertThrows(IllegalArgumentException::class.java) {
            inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, 3L, observeStorageRefusals = false)
        }
        assertTrue(requests.isEmpty())
        assertEquals(0L, inbox.storageRefusalCursor)
    }

    @Test
    fun `observeStorageRefusals = false sends no boundary on any window and never surfaces a refusal`() {
        // A 1 s server window and a 2.1 s budget: at least two chained windows, each echoing a higher count.
        val short = TestInboxClient("tk_unit", "http://localhost:${server.address.port}", Duration.ofSeconds(1))
        script(200, inboxJson(count = 3, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        val inbox = short.getInboxBlocking("x")
        // One spare answer, so an unscripted 500 can never stand in for a window.
        repeat(4) { script(200, timeout(5), delayMillis = 1_000) }
        assertThrows(TestInboxTimeoutException::class.java) {
            inbox.awaitMessageBlocking(Duration.ofMillis(2_100), MessageMatcher.ANY, null, observeStorageRefusals = false)
        }
        val bodies = waitBodies()
        assertTrue(bodies.size in 2..3, "windows: ${bodies.size}")
        bodies.forEach { assertFalse(it.contains("afterStorageRefusalCount"), it) }
        assertEquals(3L, inbox.storageRefusalCursor)
    }

    @Test
    fun `a MATCHED echo above the cursor is not adopted, and the snapshot is untouched`() {
        script(200, inboxJson(count = 2, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        script(200, matched(3))
        script(200, matched(3))
        val inbox = client.getInboxBlocking("x")
        inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        assertEquals(2L, inbox.storageRefusalCursor)
        assertEquals(2L, inbox.storageRefusalCount)
        inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        assertEquals(2L, boundarySent(waitBodies()[1])) // the next wait may therefore surface refusal 3
    }

    @Test
    fun `TIMEOUT echoes above the cursor are not adopted across chained windows`() {
        val short = TestInboxClient("tk_unit", "http://localhost:${server.address.port}", Duration.ofSeconds(1))
        script(200, inboxJson(count = 4, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        val inbox = short.getInboxBlocking("x")
        repeat(4) { script(200, timeout(6), delayMillis = 1_000) }
        assertThrows(TestInboxTimeoutException::class.java) { inbox.awaitMessageBlocking(Duration.ofMillis(2_100)) }
        val bodies = waitBodies()
        assertTrue(bodies.size in 2..3, "windows: ${bodies.size}")
        bodies.forEach { assertEquals(4L, boundarySent(it), it) }
        assertEquals(4L, inbox.storageRefusalCursor)
    }

    // --- the typed exception -----------------------------------------------------------------------

    @Test
    fun `the storage-limit 409 is the typed exception with every field, status and problem type`() {
        script(201, inboxJson())
        script(409, refused(reason = "WORKSPACE_LIMIT", boundary = 2, count = 3, scope = ""","quota":"STORED_BYTES","limit":2147483648,"current":2147480000"""))
        val inbox = client.createInboxBlocking()
        val error = assertThrows(TestInboxStorageLimitExceededException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals(409, error.status)
        assertEquals("https://testinbox.email/problems/storage-limit-exceeded", error.problemType)
        assertEquals("corr-409", error.correlationId)
        assertEquals("11111111-1111-1111-1111-111111111111", error.inboxId)
        assertEquals(StorageRefusalReason.WORKSPACE_LIMIT, error.refusalReason)
        assertEquals(2L, error.afterStorageRefusalCount)
        assertEquals(3L, error.storageRefusalCount)
        assertEquals(Instant.parse("2026-10-07T12:00:30Z"), error.lastStorageRefusalAt)
        assertEquals("STORED_BYTES", error.quota)
        assertEquals(2147483648L, error.limit)
        assertEquals(2147480000L, error.current)
        assertFalse(error.message!!.contains("tk_unit"))
        assertFalse(error.toString().contains("tk_unit"))
        // Exactly one request: a storage refusal is never retried.
        assertEquals(1, waitBodies().size)
    }

    @Test
    fun `the cursor is advanced to the 409's count BEFORE the caller's handler runs`() {
        script(201, inboxJson())
        script(409, refused(count = 3))
        val inbox = client.createInboxBlocking()
        var cursorInHandler: Long? = null
        try {
            inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        } catch (e: TestInboxStorageLimitExceededException) {
            cursorInHandler = inbox.storageRefusalCursor
        }
        assertEquals(3L, cursorInHandler)
        assertEquals(0L, inbox.storageRefusalCount) // the snapshot is a snapshot
        script(200, matched(3))
        inbox.awaitMessageBlocking(Duration.ofSeconds(5))
        assertEquals(3L, boundarySent(waitBodies()[1]))
    }

    @Test
    fun `SERVICE_CAPACITY carries the reason and null quota, limit and current`() {
        script(201, inboxJson())
        script(409, refused(reason = "SERVICE_CAPACITY", scope = null))
        val inbox = client.createInboxBlocking()
        val error = assertThrows(TestInboxStorageLimitExceededException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals(StorageRefusalReason.SERVICE_CAPACITY, error.refusalReason)
        assertNull(error.quota)
        assertNull(error.limit)
        assertNull(error.current)
        // Structural, as an allowlist: the exception declares exactly these members and nothing else.
        assertEquals(
            setOf("inboxId", "refusalReason", "afterStorageRefusalCount", "storageRefusalCount", "lastStorageRefusalAt", "quota", "limit", "current"),
            TestInboxStorageLimitExceededException::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") || it == "Companion" || it == "STORAGE_LIMIT_EXCEEDED_TYPE" }
                .toSet(),
        )
    }

    @Test
    fun `an unknown future refusal reason is still the typed exception and still advances the cursor`() {
        script(201, inboxJson())
        script(409, refused(reason = "FUTURE_LIMIT_KIND", count = 2))
        val inbox = client.createInboxBlocking()
        val error = assertThrows(TestInboxStorageLimitExceededException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals("FUTURE_LIMIT_KIND", error.refusalReason.wire)
        assertEquals(2L, inbox.storageRefusalCursor)
    }

    @Test
    fun `a storage-limit problem missing a required member is a protocol error and the cursor does not move`() {
        script(201, inboxJson())
        val inbox = client.createInboxBlocking()
        script(
            409,
            """{"type":"https://testinbox.email/problems/storage-limit-exceeded","title":"x","status":409,
                "inboxId":"11111111-1111-1111-1111-111111111111","refusalReason":"INBOX_LIMIT","afterStorageRefusalCount":0,
                "lastStorageRefusalAt":"2026-10-07T12:00:30Z"}""",
        )
        val error = assertThrows(TestInboxProtocolException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertTrue(error.message!!.contains("storageRefusalCount"))
        // The protocol error still says where it came from.
        assertEquals(409, error.status)
        assertEquals("https://testinbox.email/problems/storage-limit-exceeded", error.problemType)
        assertEquals(0L, inbox.storageRefusalCursor)
        script(409, refused(lastAt = "not-a-date"))
        assertThrows(TestInboxProtocolException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals(0L, inbox.storageRefusalCursor)
    }

    @Test
    fun `an ordinary 409 is still the generic conflict, not the storage exception`() {
        script(201, inboxJson())
        script(409, """{"type":"https://testinbox.email/problems/address-already-reserved","title":"Reserved","status":409}""")
        val inbox = client.createInboxBlocking()
        assertThrows(TestInboxConflictException::class.java) { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals(0L, inbox.storageRefusalCursor)
    }

    // --- concurrency and independence ----------------------------------------------------------------

    @Test
    fun `two concurrent waits whose 409s complete out of order leave the maximum count`() {
        for ((first, second) in listOf(5L to 3L, 3L to 5L)) {
            requests.clear()
            responses.clear()
            script(201, inboxJson())
            val inbox = client.createInboxBlocking()
            val releaseFirst = CountDownLatch(1)
            val releaseSecond = CountDownLatch(1)
            script(409, refused(count = first), gate = releaseFirst)
            script(409, refused(count = second), gate = releaseSecond)
            val pool = Executors.newFixedThreadPool(2)
            // Which wait reaches the stub first is not controlled, so the test waits for
            // WHICHEVER completes first rather than for a particular future.
            val completion = ExecutorCompletionService<Throwable?>(pool)
            try {
                repeat(2) {
                    completion.submit(Callable<Throwable?> { runCatching { inbox.awaitMessageBlocking(Duration.ofSeconds(10)) }.exceptionOrNull() })
                }
                // Both requests are parked on the stub before either answer is released.
                val until = System.nanoTime() + 5_000_000_000L
                while (waitBodies().size < 2) check(System.nanoTime() < until) { "both waits must be in flight" }
                releaseFirst.countDown()
                val firstDone = checkNotNull(completion.poll(10, TimeUnit.SECONDS)) { "no wait completed after the first release" }.get()
                assertTrue(firstDone is TestInboxStorageLimitExceededException)
                releaseSecond.countDown()
                val secondDone = checkNotNull(completion.poll(10, TimeUnit.SECONDS)) { "no wait completed after the second release" }.get()
                assertTrue(secondDone is TestInboxStorageLimitExceededException)
                assertEquals(5L, inbox.storageRefusalCursor, "order $first then $second")
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `two Inbox objects for the same server inbox keep independent cursors`() {
        script(200, inboxJson(count = 2, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        script(200, inboxJson(count = 2, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        val a = client.getInboxBlocking("x")
        val b = client.getInboxBlocking("x")
        script(409, refused(boundary = 2, count = 3))
        assertThrows(TestInboxStorageLimitExceededException::class.java) { a.awaitMessageBlocking(Duration.ofSeconds(5)) }
        assertEquals(3L, a.storageRefusalCursor)
        assertEquals(2L, b.storageRefusalCursor)
    }

    @Test
    fun `a fresh object initialises from the server's count and a persisted boundary resumes explicitly`() {
        script(200, inboxJson(count = 1, lastAt = "2026-10-07T11:59:00Z", reason = "INBOX_LIMIT"))
        script(200, matched(1))
        val fresh = client.getInboxBlocking("x")
        assertEquals(1L, fresh.storageRefusalCursor)
        fresh.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.ANY, 6L)
        assertEquals(6L, boundarySent(waitBodies().single()))
        assertEquals(6L, fresh.storageRefusalCursor)
    }

    // --- source and binary compatibility -------------------------------------------------------------

    @Test
    fun `the documented calls compile and run with no storage argument mentioned`() {
        script(201, inboxJson())
        script(200, matched(0))
        script(200, matched(0))
        val inbox = client.createInboxBlocking()
        inbox.awaitMessageBlocking(Duration.ofSeconds(5), MessageMatcher.builder().subjectContains("hi").build())
        runBlocking { inbox.awaitMessage(Duration.ofSeconds(5)) { subjectContains("hi") } }
        assertNotNull(inbox)
    }

    @Test
    fun `the pre-existing public JVM descriptors of the wait and client surface still exist`() {
        val inbox = Inbox::class.java
        // Blocking facade: the one- and two-argument forms a released caller compiled against.
        assertNotNull(inbox.getMethod("awaitMessageBlocking", Duration::class.java))
        assertNotNull(inbox.getMethod("awaitMessageBlocking", Duration::class.java, MessageMatcher::class.java))
        assertNotNull(inbox.getMethod("awaitMessageBlocking"))
        // Coroutine form: the two-argument descriptor still takes exactly (Duration, MessageMatcher, Continuation).
        val continuation = Class.forName("kotlin.coroutines.Continuation")
        assertNotNull(inbox.getMethod("awaitMessage", Duration::class.java, MessageMatcher::class.java, continuation))
        assertNotNull(inbox.getMethod("awaitMessage", Duration::class.java, continuation))
        // The new surface, Java-shaped.
        assertNotNull(inbox.getMethod("awaitMessageBlocking", Duration::class.java, MessageMatcher::class.java, java.lang.Long::class.java))
        assertNotNull(
            inbox.getMethod(
                "awaitMessageBlocking",
                Duration::class.java,
                MessageMatcher::class.java,
                java.lang.Long::class.java,
                java.lang.Boolean.TYPE,
            ),
        )
        assertNotNull(inbox.getMethod("getStorageRefusalCursor"))
        assertNotNull(inbox.getMethod("getStorage"))
        assertNotNull(TestInboxClient::class.java.getMethod("getWorkspaceStorageBlocking"))
        assertNotNull(TestInboxClient::class.java.getMethod("getInboxBlocking", String::class.java))
    }
}
