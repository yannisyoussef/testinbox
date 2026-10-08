package email.testinbox.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A member of the WRONG TYPE (a count sent as a string, a boolean sent as a
 * word) is a contract violation like an omitted one: a typed protocol error,
 * never the serialisation library's own exception escaping the public API and
 * never a generic conflict that hides what went wrong (docs/sdk/principles.md
 * #6). The cursor never moves on such a body.
 */
class StorageProtocolRobustnessTest {
    private lateinit var server: HttpServer
    private lateinit var client: TestInboxClient
    private val responses = ConcurrentLinkedQueue<Pair<Int, String>>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val (status, body) = responses.poll() ?: (500 to """{"title":"unscripted"}""")
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", if (status >= 400) "application/problem+json" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        server.start()
        client = TestInboxClient("tk_unit", "http://localhost:${server.address.port}", Duration.ofSeconds(60))
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private fun script(status: Int, body: String) {
        responses += status to body
    }

    private val inboxPrefix =
        """{"id":"11111111-1111-1111-1111-111111111111","address":"a@testinbox.local","addressMode":"GENERATED",""" +
            """"state":"ACTIVE","createdAt":"2026-10-07T12:00:00Z","expiresAt":"2026-10-07T12:15:00Z""""

    private fun inboxWith(members: String) = "$inboxPrefix,$members}"

    private fun storageLimitProblem(members: String) =
        """{"type":"https://testinbox.email/problems/storage-limit-exceeded","title":"Storage limit exceeded","status":409,""" +
            """"inboxId":"11111111-1111-1111-1111-111111111111","refusalReason":"INBOX_LIMIT",""" +
            """"lastStorageRefusalAt":"2026-10-07T12:00:30Z",$members}"""

    @Test
    fun `a storage-limit problem with a wrong-typed member is a protocol error naming the problem, not a generic conflict, and the cursor does not move`() {
        script(201, inboxWith(""""storageRefusalCount":0"""))
        val inbox = client.createInboxBlocking()
        // A quoted integer ("3") is accepted by the JSON layer as 3 on the JVM, so it is not a
        // contract violation here; the cases below cannot be read as a count at all.
        val malformed =
            listOf(
                """"afterStorageRefusalCount":0,"storageRefusalCount":1.5""",
                """"afterStorageRefusalCount":"soon","storageRefusalCount":3""",
                """"afterStorageRefusalCount":0,"storageRefusalCount":-3""",
            )
        for (members in malformed) {
            script(409, storageLimitProblem(members))
            val error = assertThrows(TestInboxProtocolException::class.java, { inbox.awaitMessageBlocking(Duration.ofSeconds(5)) }, members)
            assertTrue(error.message!!.contains("storage-limit-exceeded"), error.message)
            assertEquals(0L, inbox.storageRefusalCursor, members)
        }
    }

    @Test
    fun `a wrong-typed storage member on a 200 is a protocol error, never a serialization exception escaping`() {
        script(200, inboxWith(""""storage":{"limitBytes":1,"storedBytes":"lots","reservedBytes":0,"availableBytes":0,"overLimit":false}"""))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
        script(200, inboxWith(""""storageRefusalCount":1.5"""))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
        script(200, inboxWith(""""storage":"full""""))
        assertThrows(TestInboxProtocolException::class.java) { client.getInboxBlocking("x") }
        script(200, """{"limitBytes":1,"storedBytes":2,"reservedBytes":3,"availableBytes":0,"overLimit":"no"}""")
        assertThrows(TestInboxProtocolException::class.java) { client.getWorkspaceStorageBlocking() }
    }

    @Test
    fun `an undecodable success body anywhere is a protocol error, not a serialization exception`() {
        script(201, """{"id":42}""")
        assertThrows(TestInboxProtocolException::class.java) { client.createInboxBlocking() }
        script(200, "not json at all")
        assertThrows(TestInboxProtocolException::class.java) { client.getWorkspaceStorageBlocking() }
    }
}
