package email.testinbox.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.domain.InboxId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.io.BufferedReader
import java.net.Socket
import java.time.Duration
import java.util.UUID

/**
 * The stream's slot through HTTP (ADR-027 §7): a workspace out of slots is
 * refused `429`, and a client that goes away gives its slot back long before
 * the window would have ended — a disconnected stream must not hold a slot
 * to the deadline, or one dropped console tab blocks every wait of the
 * workspace for a whole window.
 */
@TestPropertySource(
    properties = [
        "testinbox.limits.max-concurrent-waits=1",
        "testinbox.wait-window-cap=20s",
    ],
)
class WorkspaceStreamSlotApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var jdbc: JdbcClient

    private var workspace: email.testinbox.domain.WorkspaceId? = null

    private fun liveSlots(): Long =
        jdbc
            .sql("SELECT count(*) AS c FROM wait_lease WHERE workspace_id = :workspaceId AND expires_at > now()")
            .param("workspaceId", checkNotNull(workspace).value)
            .query { rs, _ -> rs.getLong("c") }
            .single()

    /** A raw HTTP/1.1 stream whose socket can be dropped the way a closed tab drops it. */
    private inner class RawStream(
        key: String,
    ) : AutoCloseable {
        private val socket = Socket("localhost", port)
        private val reader: BufferedReader = socket.getInputStream().bufferedReader()
        val statusLine: String

        init {
            socket.getOutputStream().write(
                (
                    "GET /v1/workspace/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $key\r\n" +
                        "Accept: text/event-stream\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
            )
            socket.getOutputStream().flush()
            statusLine = reader.readLine()
        }

        fun awaitLine(prefix: String) {
            while (true) {
                val line = reader.readLine() ?: error("stream ended before a '$prefix' line")
                if (line.startsWith(prefix)) return
            }
        }

        override fun close() = socket.close()
    }

    @Test
    fun `a second stream is refused 429 while one parks, and a dropped connection frees the slot before the window ends`() {
        val tenant = provisionIsolatedWorkspace("stream-slot")
        workspace = tenant.workspaceId
        val inbox = json.readTree(post("/v1/inboxes", """{}""", tenant.apiKey).body)
        val inboxId = InboxId(UUID.fromString(inbox["id"].asText()))
        val parked = RawStream(tenant.apiKey)
        parked.statusLine shouldContain "200"
        parked.awaitLine("retry:")
        await().atMost(Duration.ofSeconds(10)).until { liveSlots() == 1L }

        val refused = get("/v1/workspace/events", tenant.apiKey)
        refused.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
        refused.headers.getFirst("Retry-After") shouldBe "1"
        val problem = json.readTree(refused.body)
        problem["type"].asText() shouldBe "https://testinbox.email/problems/concurrent-wait-limit-exceeded"
        problem["limit"].asLong() shouldBe 1
        // A refused stream claimed nothing.
        liveSlots() shouldBe 1L

        // The client goes away. The server learns of it on its next write, so
        // deliveries keep arriving until the broken connection is noticed; the
        // window itself has 20 s left, which is what must NOT be waited for.
        val freedAfter = dropAndAwaitRelease(parked, inboxId, inbox["address"].asText(), tenant.workspaceId)
        (freedAfter < Duration.ofSeconds(10)) shouldBe true

        // And the workspace can stream again at once — then leave nothing parked behind.
        val again = RawStream(tenant.apiKey)
        again.statusLine shouldContain "200"
        again.awaitLine("retry:")
        dropAndAwaitRelease(again, inboxId, inbox["address"].asText(), tenant.workspaceId)
    }

    private fun dropAndAwaitRelease(
        stream: RawStream,
        inboxId: InboxId,
        address: String,
        workspaceId: email.testinbox.domain.WorkspaceId,
    ): Duration {
        val droppedAt = System.nanoTime()
        stream.close()
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(200)).until {
            appendVisibleMessage(inboxId, address, subject = "poke", workspaceId = workspaceId)
            liveSlots() == 0L
        }
        return Duration.ofNanos(System.nanoTime() - droppedAt)
    }
}
