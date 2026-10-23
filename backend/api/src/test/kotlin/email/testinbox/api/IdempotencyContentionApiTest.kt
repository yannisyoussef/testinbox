package email.testinbox.api

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The two `409`s that only appear under contention or after a rollback
 * (ADR-033 §7).
 *
 * Both branches are rendered by `InboxController`, and neither was reachable
 * from any test: the happy-path suites assert that concurrent requests all
 * succeed, which is the *absence* of the in-progress path, and
 * `idempotency-replay-unavailable` fires only after an ADR-028 artifact
 * rollback across a snapshot version bump — the one moment nobody is
 * exercising it by hand.
 *
 * Separate from `IdempotencyApiTest` because it needs a claim wait short
 * enough to exercise in milliseconds; the shipped default is two seconds.
 */
@TestPropertySource(properties = ["testinbox.idempotency.claim-wait=250ms"])
class IdempotencyContentionApiTest : ApiIntegrationTestBase() {
    private val json = ObjectMapper()

    @Autowired
    lateinit var dataSource: DataSource

    private fun key(): String = "contention-${UUID.randomUUID()}"

    private fun postWithKey(
        path: String,
        body: String,
        idempotencyKey: String,
        apiKey: String,
    ): ResponseEntity<String> {
        val headers =
            HttpHeaders().apply {
                setBearerAuth(apiKey)
                set("Content-Type", "application/json")
                set("Idempotency-Key", idempotencyKey)
            }
        return rest.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    @Test
    fun `a claim blocked by a live one is refused as in-progress, with a retry hint`() {
        val tenant = provisionIsolatedWorkspace("idem-inflight")
        val k = key()
        val body = """{"ttlSeconds":600}"""
        postWithKey("/v1/inboxes", body, k, tenant.apiKey).statusCode shouldBe HttpStatus.CREATED

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            // Locks the workspace's only record the way a still-running claim
            // does. The retry's `ON CONFLICT DO UPDATE` then blocks on this row
            // rather than reading it, which is exactly the live-duplicate case.
            holder.submit {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    connection
                        .prepareStatement(
                            "SELECT id FROM idempotency_record WHERE workspace_id = ? FOR UPDATE",
                        ).use { statement ->
                            statement.setObject(1, tenant.workspaceId.value)
                            statement.executeQuery()
                        }
                    holding.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    connection.rollback()
                }
            }
            holding.await(10, TimeUnit.SECONDS) shouldBe true

            val blocked = postWithKey("/v1/inboxes", body, k, tenant.apiKey)
            // Never a 500: the lock timeout poisons the transaction, and the
            // refusal is rendered from values already in hand after it unwinds.
            blocked.statusCode shouldBe HttpStatus.CONFLICT
            blocked.headers.contentType.toString() shouldContain "application/problem+json"
            val problem = json.readTree(blocked.body!!)
            problem["type"].asString() shouldContain "idempotency-request-in-progress"
            // The one 409 where retrying with the SAME key is correct, so it
            // must carry a hint. Distinguishing it from key-reused is the whole
            // point of §7.
            blocked.headers.getFirst("Retry-After")!!.toLong() shouldBe 1L
        } finally {
            release.countDown()
            holder.shutdown()
            holder.awaitTermination(10, TimeUnit.SECONDS)
        }

        // Once the holder is gone the same key replays normally, so the
        // refusal was genuinely transient.
        val after = postWithKey("/v1/inboxes", body, k, tenant.apiKey)
        after.statusCode shouldBe HttpStatus.CREATED
        after.headers.getFirst("Idempotency-Replayed") shouldBe "true"
    }

    @Test
    fun `a record this artifact cannot decode is refused, never re-executed`() {
        val tenant = provisionIsolatedWorkspace("idem-rollback")
        val k = key()
        val body = """{"ttlSeconds":600}"""
        val created = postWithKey("/v1/inboxes", body, k, tenant.apiKey)
        created.statusCode shouldBe HttpStatus.CREATED
        val inboxId = json.readTree(created.body!!)["id"].asString()

        // Exactly what an ADR-028 rollback leaves behind: a committed record
        // written by a newer artifact, whose snapshot this one cannot read.
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE idempotency_record SET snapshot_version = 99 WHERE workspace_id = ?",
                ).use { statement ->
                    statement.setObject(1, tenant.workspaceId.value)
                    statement.executeUpdate() shouldBe 1
                }
        }

        val retry = postWithKey("/v1/inboxes", body, k, tenant.apiKey)
        retry.statusCode shouldBe HttpStatus.CONFLICT
        val problem = json.readTree(retry.body!!)
        // Re-executing would duplicate a mutation that did commit, and
        // guessing would lie. The honest answer is that the key is spent.
        problem["type"].asString() shouldContain "idempotency-replay-unavailable"

        // And the original mutation is untouched — the refusal reports an
        // unreadable record, it does not undo or repeat the work.
        get("/v1/inboxes/$inboxId", tenant.apiKey).statusCode shouldBe HttpStatus.OK
        countInboxes(tenant.workspaceId.value) shouldBe 1
    }

    private fun countInboxes(workspaceId: UUID): Int =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement("SELECT count(*) FROM inbox WHERE workspace_id = ?")
                .use { statement ->
                    statement.setObject(1, workspaceId)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }
}
