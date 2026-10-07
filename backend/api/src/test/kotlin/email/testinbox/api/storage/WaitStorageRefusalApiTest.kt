package email.testinbox.api.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.persistence.JdbcStorageReservations
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * ADR-035 §13c over HTTP (§17 tests 37–43): the raw REST cursor, the `409`
 * problem, the deciding-snapshot echo, the legacy guarantee for callers that
 * omit the cursor, and refusals produced by the real guarded protocol.
 *
 * The server wait window is capped at 5 s in this suite
 * (`testinbox.wait-window-cap`), so a `409` that arrives well inside it was
 * push-woken, not timed out.
 */
class WaitStorageRefusalApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var storageReservations: JdbcStorageReservations

    @Autowired lateinit var transactions: TransactionOperations

    @Autowired lateinit var guardedRefusals: GuardedRefusals

    @Autowired lateinit var hook: ScriptableWaitHook

    @Autowired lateinit var registry: MeterRegistry

    @Autowired lateinit var policy: StorageCapacityPolicy

    // One instance per class (the base is PER_CLASS), so the executor is per test, not per instance.
    private lateinit var executor: ExecutorService

    @BeforeEach
    fun setUp() {
        executor = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun tearDown() {
        hook.reset()
        executor.shutdownNow()
    }

    private class Tenant(
        val workspaceId: WorkspaceId,
        val key: String,
    )

    private fun tenant(label: String): Tenant = provisionIsolatedWorkspace(label).let { Tenant(it.workspaceId, it.apiKey) }

    private fun Tenant.createInbox(): Pair<InboxId, JsonNode> {
        val body = json.readTree(post("/v1/inboxes", """{}""", key).body)
        return InboxId(UUID.fromString(body["id"].asText())) to body
    }

    private fun Tenant.wait(
        inbox: InboxId,
        cursor: Long?,
        timeoutSeconds: Int = 1,
        matcher: String = "",
    ): ResponseEntity<String> {
        val cursorMember = if (cursor == null) "" else ""","afterStorageRefusalCount":$cursor"""
        val matcherMember = if (matcher.isEmpty()) "" else ""","matcher":$matcher"""
        return post("/v1/inboxes/${inbox.value}/messages/wait", """{"timeoutSeconds":$timeoutSeconds$cursorMember$matcherMember}""", key)
    }

    /** A refusal written exactly as T2 or the refusal-only transaction writes it, with its notify. */
    private fun recordRefusal(
        inbox: InboxId,
        reason: StorageRefusalReason = StorageRefusalReason.INBOX_LIMIT,
    ) = transactions.executeWithoutResult { storageReservations.recordRefusals(mapOf(inbox to reason)) }

    private fun leases(workspaceId: WorkspaceId): Long =
        jdbc
            .sql("SELECT count(*) FROM wait_lease WHERE workspace_id = :w")
            .param("w", workspaceId.value)
            .query(Long::class.java)
            .single()

    /** Blocks until a wait of [workspaceId] holds its slot, i.e. is genuinely parked. Ordered by state, not by sleeping. */
    private fun awaitParked(workspaceId: WorkspaceId) {
        val until = System.nanoTime() + 5_000_000_000L
        while (leases(workspaceId) == 0L) {
            check(System.nanoTime() < until) { "the wait never parked" }
            Thread.sleep(10)
        }
    }

    private fun refusedCount(): Double =
        registry
            .find("testinbox_wait_request_duration_seconds")
            .tag("outcome", "STORAGE_LIMIT_EXCEEDED")
            .timer()
            ?.count()
            ?.toDouble() ?: 0.0

    private fun ResponseEntity<String>.problem(): JsonNode {
        headers.contentType.toString() shouldContain "application/problem+json"
        return json.readTree(body)
    }

    // --- test 37: refusal before the first wait -------------------------------------------

    @Test
    fun `owner test 37 - a refusal recorded before the wait and a cursor of 0 answer 409 at once, with no slot and no park`() {
        val t = tenant("t37")
        val (inbox, created) = t.createInbox()
        created["storageRefusalCount"].asLong() shouldBe 0
        val report = guardedRefusals.refuse(t.workspaceId, inbox, StorageRefusalReason.INBOX_LIMIT)
        report.refused shouldBe mapOf(inbox to StorageRefusalReason.INBOX_LIMIT)
        val before = refusedCount()

        val response = t.wait(inbox, cursor = 0, timeoutSeconds = 5)

        response.statusCode.value() shouldBe 409
        response.headers.getFirst("Retry-After") shouldBe null
        val problem = response.problem()
        problem["type"].asText() shouldBe "https://testinbox.email/problems/storage-limit-exceeded"
        problem["inboxId"].asText() shouldBe inbox.value.toString()
        problem["refusalReason"].asText() shouldBe "INBOX_LIMIT"
        problem["afterStorageRefusalCount"].asLong() shouldBe 0
        problem["storageRefusalCount"].asLong() shouldBe 1
        problem["lastStorageRefusalAt"].isNull shouldBe false
        problem["quota"].asText() shouldBe "STORED_BYTES"
        problem["limit"].asLong() shouldBe policy.inboxLimitBytes
        problem["current"].asLong() shouldBe 0 // the refused copy was never stored or reserved
        problem.has("retryAfterSeconds") shouldBe false
        problem["correlationId"].asText().isNotBlank() shouldBe true
        // Decided at the initial check: never subscribed, never parked, never charged a slot.
        hook.pointsFor(inbox) shouldBe listOf("afterDecision")
        leases(t.workspaceId) shouldBe 0
        refusedCount() shouldBe before + 1
    }

    // --- test 38: refusal during the wait, through the real guarded protocol ------------------

    @Test
    fun `owner test 38 - a refusal committed by the guarded protocol while parked wakes the waiter into a 409, not a TIMEOUT`() {
        val t = tenant("t38")
        val (inbox, _) = t.createInbox()
        val refusal: Future<*> =
            executor.submit {
                awaitParked(t.workspaceId)
                guardedRefusals.refuse(t.workspaceId, inbox, StorageRefusalReason.WORKSPACE_LIMIT)
            }
        val started = System.nanoTime()
        val response = t.wait(inbox, cursor = 0, timeoutSeconds = 30) // capped at 5 s by the suite
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        refusal.get()

        response.statusCode.value() shouldBe 409
        val problem = response.problem()
        problem["refusalReason"].asText() shouldBe "WORKSPACE_LIMIT"
        problem["storageRefusalCount"].asLong() shouldBe 1
        problem["afterStorageRefusalCount"].asLong() shouldBe 0
        problem["quota"].asText() shouldBe "STORED_BYTES"
        problem["limit"].asLong() shouldBe policy.workspaceLimitBytes
        problem["current"].asLong() shouldBe 50 // the fixture's seeded workspace base; the refused copy itself is not counted
        // Woken by the refusal's pg_notify, well inside the 5 s window.
        elapsedMs shouldBeLessThan 4_000
        hook.pointsFor(inbox) shouldBe listOf("afterInitialCheck", "afterSubscribe", "afterDecision")
        // The slot it held was released with the outcome.
        leases(t.workspaceId) shouldBe 0
    }

    // --- test 39: refusal after the matching message committed ------------------------------

    @Test
    fun `test 39 - a match outranks a newer refusal, and the next wait with the unadvanced cursor gets the 409`() {
        val t = tenant("t39")
        val (inbox, created) = t.createInbox()
        appendVisibleMessage(inbox, created["address"].asText(), subject = "the one", workspaceId = t.workspaceId)
        recordRefusal(inbox)

        val matched = t.wait(inbox, cursor = 0, matcher = """{"subjectContains":"one"}""")
        matched.statusCode.value() shouldBe 200
        val result = json.readTree(matched.body)
        result["status"].asText() shouldBe "MATCHED"
        result["storageRefusalCount"].asLong() shouldBe 1
        result["lastStorageRefusalAt"].isNull shouldBe false

        // Same boundary, a matcher the old message does not satisfy: the refusal was not swallowed.
        t.wait(inbox, cursor = 0, matcher = """{"subjectContains":"another"}""").statusCode.value() shouldBe 409
        // Adopting the echo would skip it, and that is the caller's explicit choice.
        json.readTree(t.wait(inbox, cursor = 1, matcher = """{"subjectContains":"another"}""").body)["status"].asText() shouldBe "TIMEOUT"
    }

    // --- test 40: match racing a refusal, both orders ----------------------------------------

    @Test
    fun `test 40 - match then refusal, and refusal then match, between check and recheck - one snapshot decides and the match wins`() {
        val t = tenant("t40")
        for (refusalFirst in listOf(false, true)) {
            val (inbox, created) = t.createInbox()
            hook.onAfterSubscribe = { id ->
                if (id == inbox) {
                    if (refusalFirst) recordRefusal(inbox)
                    appendVisibleMessage(inbox, created["address"].asText(), subject = "raced", workspaceId = t.workspaceId)
                    if (!refusalFirst) recordRefusal(inbox)
                }
            }
            val response = t.wait(inbox, cursor = 0, timeoutSeconds = 5)
            response.statusCode.value() shouldBe 200
            val result = json.readTree(response.body)
            result["status"].asText() shouldBe "MATCHED"
            result["storageRefusalCount"].asLong() shouldBe 1
            // Decided by the recheck, before any park.
            hook.pointsFor(inbox) shouldBe listOf("afterInitialCheck", "afterSubscribe", "afterDecision")
            leases(t.workspaceId) shouldBe 0
            hook.reset()
        }
        // Only the refusal in the snapshot: 409, still with no slot.
        val (inbox, _) = t.createInbox()
        hook.onAfterSubscribe = { id -> if (id == inbox) recordRefusal(inbox) }
        t.wait(inbox, cursor = 0, timeoutSeconds = 5).statusCode.value() shouldBe 409
        hook.pointsFor(inbox) shouldBe listOf("afterInitialCheck", "afterSubscribe", "afterDecision")
    }

    // --- test 41: cursor edges ---------------------------------------------------------------

    @Test
    fun `test 41 - cursor equal to the count parks and times out with the count echoed, below it is a 409`() {
        val t = tenant("t41a")
        val (inbox, _) = t.createInbox()
        recordRefusal(inbox)
        recordRefusal(inbox)

        val equal = t.wait(inbox, cursor = 2)
        equal.statusCode.value() shouldBe 200
        val timeout = json.readTree(equal.body)
        timeout["status"].asText() shouldBe "TIMEOUT"
        timeout["storageRefusalCount"].asLong() shouldBe 2
        timeout["lastStorageRefusalAt"].isNull shouldBe false
        timeout["arrivedButUnmatchedCount"].asInt() shouldBe 0

        val below = t.wait(inbox, cursor = 1)
        below.statusCode.value() shouldBe 409
        below.problem()["afterStorageRefusalCount"].asLong() shouldBe 1
        below.problem()["storageRefusalCount"].asLong() shouldBe 2
    }

    @Test
    fun `test 41 - a cursor above the count is clamped once to the initial count, and the next refusal is still a 409`() {
        val t = tenant("t41b")
        val (inbox, _) = t.createInbox()
        recordRefusal(inbox) // count 1
        executor.submit {
            awaitParked(t.workspaceId)
            recordRefusal(inbox) // count 2, while parked
        }
        val response = t.wait(inbox, cursor = 100, timeoutSeconds = 5)
        response.statusCode.value() shouldBe 409
        val problem = response.problem()
        problem["afterStorageRefusalCount"].asLong() shouldBe 1 // min(100, 1), fixed at the first evaluation
        problem["storageRefusalCount"].asLong() shouldBe 2

        // Long.MAX_VALUE behaves the same way.
        val (other, _) = t.createInbox()
        recordRefusal(other)
        executor.submit {
            awaitParked(t.workspaceId)
            recordRefusal(other)
        }
        val max = t.wait(other, cursor = Long.MAX_VALUE, timeoutSeconds = 5)
        max.statusCode.value() shouldBe 409
        max.problem()["afterStorageRefusalCount"].asLong() shouldBe 1
    }

    @Test
    fun `test 41 - a negative or malformed cursor is a 400 invalid request`() {
        val t = tenant("t41c")
        val (inbox, _) = t.createInbox()
        val negative = t.wait(inbox, cursor = -1)
        negative.statusCode.value() shouldBe 400
        negative.problem()["type"].asText() shouldBe "https://testinbox.email/problems/invalid-request"
        negative.problem()["detail"].asText() shouldContain "afterStorageRefusalCount"
        post("/v1/inboxes/${inbox.value}/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":"soon"}""", t.key)
            .statusCode
            .value() shouldBe 400
    }

    @Test
    fun `test 41 and the legacy guarantee - an omitted cursor never gets a 409, and TIMEOUT still carries the count`() {
        val t = tenant("t41d")
        val (inbox, created) = t.createInbox()
        recordRefusal(inbox)
        recordRefusal(inbox)

        // Refusals already recorded.
        val timeout = t.wait(inbox, cursor = null)
        timeout.statusCode.value() shouldBe 200
        json.readTree(timeout.body)["status"].asText() shouldBe "TIMEOUT"
        json.readTree(timeout.body)["storageRefusalCount"].asLong() shouldBe 2

        // Another refusal arriving while parked: woken, re-evaluated, and STILL a timeout.
        executor.submit {
            awaitParked(t.workspaceId)
            guardedRefusals.refuse(t.workspaceId, inbox, StorageRefusalReason.INBOX_LIMIT)
        }
        val woken = t.wait(inbox, cursor = null, timeoutSeconds = 2)
        woken.statusCode.value() shouldBe 200
        json.readTree(woken.body)["status"].asText() shouldBe "TIMEOUT"
        json.readTree(woken.body)["storageRefusalCount"].asLong() shouldBe 3

        // And a message still matches as before.
        appendVisibleMessage(inbox, created["address"].asText(), workspaceId = t.workspaceId)
        json.readTree(t.wait(inbox, cursor = null).body)["status"].asText() shouldBe "MATCHED"

        // Zero is not absence: the same inbox with cursor 0 is a 409.
        t.wait(inbox, cursor = 0, matcher = """{"subjectContains":"nothing-like-this"}""").statusCode.value() shouldBe 409
    }

    // --- test 42: the echo comes from the deciding snapshot ------------------------------------

    @Test
    fun `test 42 - a refusal committed after the decision but before the response is not echoed, by MATCHED or by TIMEOUT`() {
        val t = tenant("t42")
        val (inbox, created) = t.createInbox()
        hook.onAfterDecision = { id -> if (id == inbox) recordRefusal(inbox) }

        appendVisibleMessage(inbox, created["address"].asText(), workspaceId = t.workspaceId)
        val matched = json.readTree(t.wait(inbox, cursor = 0).body)
        matched["status"].asText() shouldBe "MATCHED"
        matched["storageRefusalCount"].asLong() shouldBe 0 // N, not N+1
        matched["lastStorageRefusalAt"].isNull shouldBe true
        json.readTree(get("/v1/inboxes/${inbox.value}", t.key).body)["storageRefusalCount"].asLong() shouldBe 1

        val timeout = json.readTree(t.wait(inbox, cursor = 1, matcher = """{"subjectContains":"no-such-subject"}""").body)
        timeout["status"].asText() shouldBe "TIMEOUT"
        timeout["storageRefusalCount"].asLong() shouldBe 1 // the snapshot's N, though the record is now 2
        json.readTree(get("/v1/inboxes/${inbox.value}", t.key).body)["storageRefusalCount"].asLong() shouldBe 2
    }

    // --- test 43, raw REST portion: chained waits, independent callers, no server-side cursor ------

    @Test
    fun `test 43 - chained raw REST waits with a caller-managed cursor observe each refusal exactly once, and independently per caller`() {
        val t = tenant("t43")
        val (inbox, created) = t.createInbox()
        var cursor = created["storageRefusalCount"].asLong() // 0, from the create response

        recordRefusal(inbox)
        val first = t.wait(inbox, cursor = cursor).problem()
        first["storageRefusalCount"].asLong() shouldBe 1
        cursor = first["storageRefusalCount"].asLong() // the caller handles the 409 and moves its boundary

        json.readTree(t.wait(inbox, cursor = cursor).body)["status"].asText() shouldBe "TIMEOUT"

        recordRefusal(inbox)
        val second = t.wait(inbox, cursor = cursor).problem()
        second["afterStorageRefusalCount"].asLong() shouldBe 1
        second["storageRefusalCount"].asLong() shouldBe 2

        // A second, independent caller (a "restarted" process, or another SDK instance) with its own boundary.
        val secondKey =
            provisionAdditionalKey(
                email.testinbox.api.ApiIntegrationTestBase
                    .IsolatedTenant(t.workspaceId, projectOf(t.workspaceId), t.key),
            )
        val independent =
            post("/v1/inboxes/${inbox.value}/messages/wait", """{"timeoutSeconds":1,"afterStorageRefusalCount":0}""", secondKey)
        independent.statusCode.value() shouldBe 409
        independent.problem()["afterStorageRefusalCount"].asLong() shouldBe 0
        // A caller that re-reads the inbox and accepts its current count observes nothing old.
        val current = json.readTree(get("/v1/inboxes/${inbox.value}", t.key).body)["storageRefusalCount"].asLong()
        json.readTree(t.wait(inbox, cursor = current).body)["status"].asText() shouldBe "TIMEOUT"

        // The server keeps no per-client observation state: no table or column of the schema holds a cursor.
        jdbc
            .sql(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND (column_name ILIKE '%cursor%' OR table_name ILIKE '%cursor%' OR table_name ILIKE '%observation%')",
            ).query(Long::class.java)
            .single() shouldBe 0
    }

    private fun projectOf(workspaceId: WorkspaceId): email.testinbox.domain.ProjectId =
        email.testinbox.domain.ProjectId(
            jdbc
                .sql(
                    "SELECT id FROM project WHERE workspace_id = :w LIMIT 1",
                ).param("w", workspaceId.value)
                .query(UUID::class.java)
                .single(),
        )

    // --- the three refusal kinds, as the 409 renders them ------------------------------------

    @Test
    fun `each refusal reason renders its own 409 - tenant scopes carry quota, limit and current, SERVICE_CAPACITY carries none of them`() {
        val t = tenant("reasons")
        val (inboxLimited, _) = t.createInbox()
        guardedRefusals.refuse(t.workspaceId, inboxLimited, StorageRefusalReason.INBOX_LIMIT)
        val inbox = t.wait(inboxLimited, cursor = 0).problem()
        inbox["refusalReason"].asText() shouldBe "INBOX_LIMIT"
        inbox["quota"].asText() shouldBe "STORED_BYTES"
        inbox["limit"].asLong() shouldBe policy.inboxLimitBytes
        inbox.has("current") shouldBe true

        val (capacity, _) = t.createInbox()
        guardedRefusals.refuse(t.workspaceId, capacity, StorageRefusalReason.SERVICE_CAPACITY)
        val service = t.wait(capacity, cursor = 0).problem()
        service["refusalReason"].asText() shouldBe "SERVICE_CAPACITY"
        service["storageRefusalCount"].asLong() shouldBe 1
        // Absent, not zero (ADR-035 §13d).
        service.has("quota") shouldBe false
        service.has("limit") shouldBe false
        service.has("current") shouldBe false
    }
}
