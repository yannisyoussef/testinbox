package email.testinbox.api.storage

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.notification.PgListenNotifier
import email.testinbox.persistence.JdbcStorageReservations
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionOperations
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * ADR-035 §13c response precedence (§17 test 44): `409` before the slot
 * `429` and without consuming a slot; a refusal on another inbox does not end
 * the wait; a killed LISTEN still surfaces the `409`; `404` and `410` are
 * unchanged. One slot per workspace, so the slot refusal is decisive.
 */
@TestPropertySource(properties = ["testinbox.limits.max-concurrent-waits=1"])
class WaitStoragePrecedenceApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var storageReservations: JdbcStorageReservations

    @Autowired lateinit var transactions: TransactionOperations

    @Autowired lateinit var guardedRefusals: GuardedRefusals

    @Autowired lateinit var notifier: PgListenNotifier

    @Autowired lateinit var hook: ScriptableWaitHook

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

    private fun recordRefusal(inbox: InboxId) =
        transactions.executeWithoutResult { storageReservations.recordRefusals(mapOf(inbox to StorageRefusalReason.INBOX_LIMIT)) }

    private fun leases(workspaceId: WorkspaceId): Long =
        jdbc
            .sql("SELECT count(*) FROM wait_lease WHERE workspace_id = :w")
            .param("w", workspaceId.value)
            .query(Long::class.java)
            .single()

    private fun awaitLeases(
        workspaceId: WorkspaceId,
        expected: Long,
    ) {
        val until = System.nanoTime() + 5_000_000_000L
        while (leases(workspaceId) != expected) {
            check(System.nanoTime() < until) { "expected $expected leases, saw ${leases(workspaceId)}" }
            Thread.sleep(10)
        }
    }

    private fun wait(
        key: String,
        inbox: InboxId,
        cursor: Long?,
        timeoutSeconds: Int,
    ): ResponseEntity<String> {
        val member = if (cursor == null) "" else ""","afterStorageRefusalCount":$cursor"""
        return post("/v1/inboxes/${inbox.value}/messages/wait", """{"timeoutSeconds":$timeoutSeconds$member}""", key)
    }

    private fun createInbox(key: String): InboxId =
        InboxId(UUID.fromString(json.readTree(post("/v1/inboxes", """{}""", key).body)["id"].asText()))

    @Test
    fun `test 44 - an already-visible refusal is a 409 ahead of the concurrent-wait 429, and consumes no slot`() {
        val tenant = provisionIsolatedWorkspace("prec-slot")
        val refused = createInbox(tenant.apiKey)
        val idle = createInbox(tenant.apiKey)
        val another = createInbox(tenant.apiKey)
        guardedRefusals.refuse(tenant.workspaceId, refused, StorageRefusalReason.INBOX_LIMIT)

        // Someone else's wait holds the workspace's only slot.
        val parked: Future<ResponseEntity<String>> =
            executor.submit<ResponseEntity<String>> {
                wait(tenant.apiKey, idle, cursor = null, timeoutSeconds = 4)
            }
        awaitLeases(tenant.workspaceId, 1)

        val conflict = wait(tenant.apiKey, refused, cursor = 0, timeoutSeconds = 4)
        conflict.statusCode.value() shouldBe 409
        json.readTree(conflict.body)["type"].asText() shouldContain "storage-limit-exceeded"
        leases(tenant.workspaceId) shouldBe 1 // still only the parked one: the 409 claimed nothing

        // The same slot situation without a refusal is the slot refusal, as before.
        val slot = wait(tenant.apiKey, another, cursor = 0, timeoutSeconds = 4)
        slot.statusCode.value() shouldBe 429
        json.readTree(slot.body)["type"].asText() shouldContain "concurrent-wait-limit-exceeded"
        slot.headers.getFirst("Retry-After") shouldBe "1"

        json.readTree(parked.get().body)["status"].asText() shouldBe "TIMEOUT"
        leases(tenant.workspaceId) shouldBe 0
    }

    @Test
    fun `test 44 - a refusal on another inbox does not end this wait`() {
        val tenant = provisionIsolatedWorkspace("prec-other")
        val a = createInbox(tenant.apiKey)
        val b = createInbox(tenant.apiKey)
        executor.submit {
            awaitLeases(tenant.workspaceId, 1)
            recordRefusal(b)
            guardedRefusals.refuse(tenant.workspaceId, b, StorageRefusalReason.INBOX_LIMIT)
        }
        val response = wait(tenant.apiKey, a, cursor = 0, timeoutSeconds = 2)
        response.statusCode.value() shouldBe 200
        val result = json.readTree(response.body)
        result["status"].asText() shouldBe "TIMEOUT"
        result["storageRefusalCount"].asLong() shouldBe 0
        json.readTree(get("/v1/inboxes/${b.value}", tenant.apiKey).body)["storageRefusalCount"].asLong() shouldBe 2
    }

    @Test
    fun `test 44 - with the LISTEN connection killed, a refusal still surfaces as a 409 within the bounded re-query, not a TIMEOUT`() {
        val tenant = provisionIsolatedWorkspace("prec-listen")
        val inbox = createInbox(tenant.apiKey)
        // A live LISTEN first, or killing it would prove nothing.
        await().atMost(Duration.ofSeconds(10)).until { notifier.health().listening }
        val epochBefore = notifier.health().epoch
        val reconnectsBefore = notifier.health().reconnectCount
        executor.submit {
            awaitLeases(tenant.workspaceId, 1)
            // Sever the session-scoped LISTEN connection while the waiter is parked.
            jdbc
                .sql(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
                        "WHERE application_name LIKE 'testinbox-listen:%' AND pid <> pg_backend_pid()",
                ).query()
                .listOfRows()
            await().atMost(Duration.ofSeconds(5)).until { !notifier.health().listening || notifier.health().epoch > epochBefore }
            // A refusal whose notify may now be lost.
            guardedRefusals.refuse(tenant.workspaceId, inbox, StorageRefusalReason.INBOX_LIMIT)
        }
        val started = System.nanoTime()
        val response = wait(tenant.apiKey, inbox, cursor = 0, timeoutSeconds = 30) // capped at 5 s
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        response.statusCode.value() shouldBe 409
        json.readTree(response.body)["storageRefusalCount"].asLong() shouldBe 1
        // Found by the re-LISTEN re-query or the degraded tick, inside the window.
        elapsedMs shouldBeLessThan 4_900
        // The kill really happened, and the transport recovered on its own.
        await().atMost(Duration.ofSeconds(10)).until { notifier.health().listening && notifier.health().epoch > epochBefore }
        notifier.health().reconnectCount shouldBeGreaterThan reconnectsBefore
    }

    @Test
    fun `test 44 - 404 and 410 are unchanged by the cursor, and a cross-tenant inbox is 404`() {
        val tenant = provisionIsolatedWorkspace("prec-404")
        val missing = wait(tenant.apiKey, InboxId(UUID.randomUUID()), cursor = 0, timeoutSeconds = 1)
        missing.statusCode.value() shouldBe 404
        json.readTree(missing.body)["type"].asText() shouldContain "inbox-not-found"

        val foreign = createInbox(otherWorkspaceKey)
        recordRefusal(foreign)
        val crossTenant = wait(tenant.apiKey, foreign, cursor = 0, timeoutSeconds = 1)
        crossTenant.statusCode.value() shouldBe 404
        crossTenant.body!!.contains("storage") shouldBe false

        val deleted = createInbox(tenant.apiKey)
        recordRefusal(deleted)
        delete("/v1/inboxes/${deleted.value}", tenant.apiKey).statusCode.value() shouldBe 204
        val gone = wait(tenant.apiKey, deleted, cursor = 0, timeoutSeconds = 1)
        // The sweep may hard-delete between the DELETE and the wait: 410 or 404, never 409.
        check(gone.statusCode.value() in setOf(404, 410)) { "got ${gone.statusCode}" }
    }
}
