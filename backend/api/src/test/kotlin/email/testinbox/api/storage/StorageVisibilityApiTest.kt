package email.testinbox.api.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.persistence.JdbcStorageReservations
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import java.util.UUID

/**
 * ADR-035 §13a/§13b through HTTP (§17 test 46): `StorageUsage` for the
 * workspace and the inbox equals the accounting, the inbox's `availableBytes`
 * is the minimum of inbox and workspace headroom, and an idempotent create
 * replay returns live fields.
 */
class StorageVisibilityApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var storageReservations: JdbcStorageReservations

    @Autowired lateinit var transactions: TransactionOperations

    @Autowired lateinit var policy: StorageCapacityPolicy

    @Autowired lateinit var guardedRefusals: GuardedRefusals

    // --- seeding the three sums directly, as T2 and the compactor would leave them ---------

    private fun workspaceBase(
        ws: WorkspaceId,
        bytes: Long,
    ) = jdbc
        .sql(
            "INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (:w, :b) " +
                "ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes",
        ).param("w", ws.value)
        .param("b", bytes)
        .update()

    private fun inboxBase(
        ws: WorkspaceId,
        inbox: InboxId,
        bytes: Long,
    ) = jdbc
        .sql(
            "INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes) VALUES (:i, :w, :b) " +
                "ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = inbox_storage.base_bytes + EXCLUDED.base_bytes",
        ).param("i", inbox.value)
        .param("w", ws.value)
        .param("b", bytes)
        .update()

    private fun delta(
        ws: WorkspaceId,
        inbox: InboxId?,
        bytes: Long,
    ) = jdbc
        .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES (:w, :i, :b)")
        .param("w", ws.value)
        .param("i", inbox?.value)
        .param("b", bytes)
        .update()

    private fun reservation(
        ws: WorkspaceId,
        inbox: InboxId,
        bytes: Long,
        state: String = "RESERVED",
    ) = jdbc
        .sql(
            """
            INSERT INTO storage_reservation
                (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, release_not_before, node_id, generation)
            VALUES (:m, :w, :i, ARRAY['k']::text[], :b, :s, now() - interval '1 day', now() - interval '1 day' + interval '2 minutes',
                    CASE WHEN :s = 'RELEASING' THEN now() + interval '17 minutes' END, 'seed', :g)
            """.trimIndent(),
        ).param("m", UUID.randomUUID())
        .param("w", ws.value)
        .param("i", inbox.value)
        .param("b", bytes)
        .param("s", state)
        .param("g", UUID.randomUUID())
        .update()

    private fun refuse(
        inbox: InboxId,
        reason: StorageRefusalReason,
    ) = transactions.executeWithoutResult { storageReservations.recordRefusals(mapOf(inbox to reason)) }

    private fun createInbox(key: String): JsonNode {
        val response = post("/v1/inboxes", """{}""", key)
        response.statusCode.value() shouldBe 201
        return json.readTree(response.body)
    }

    private fun JsonNode.usage() = StorageUsageShape(this)

    private class StorageUsageShape(
        node: JsonNode,
    ) {
        val limit = node["limitBytes"].asLong()
        val stored = node["storedBytes"].asLong()
        val reserved = node["reservedBytes"].asLong()
        val available = node["availableBytes"].asLong()
        val overLimit = node["overLimit"].asBoolean()

        init {
            check(!node.has("committedBytes")) { "the internal name must never reach the wire" }
            check(node.size() == 5) { "exactly the five ADR-035 §13 members, got ${node.fieldNames().asSequence().toList()}" }
        }
    }

    private val inboxLimit: Long get() = policy.inboxLimitBytes
    private val workspaceLimit: Long get() = policy.workspaceLimitBytes

    @Test
    fun `the API's policy is the one effective policy, derived from the configured limits`() {
        // 1 GB in the test configuration; the inbox share is the ADR-035 reference 0.25.
        workspaceLimit shouldBe 1_000_000_000L
        inboxLimit shouldBe 250_000_000L
        val expected =
            EffectiveStoragePolicy.of(
                email.testinbox.application
                    .LimitsProperties(maxStoredBytes = 1_000_000_000L)
                    .toConfig(),
            )
        listOf(policy.workspaceLimitBytes, policy.inboxLimitBytes, policy.globalLimitBytes, policy.finalizeBudgetBytes) shouldBe
            listOf(expected.workspaceLimitBytes, expected.inboxLimitBytes, expected.globalLimitBytes, expected.finalizeBudgetBytes)
    }

    @Test
    fun `GET workspace storage is the authenticated workspace's base plus delta plus reservations, divided into stored and reserved`() {
        val tenant = provisionIsolatedWorkspace("ws-storage")
        val inbox = InboxId(UUID.fromString(createInbox(tenant.apiKey)["id"].asText()))
        workspaceBase(tenant.workspaceId, 1_000)
        delta(tenant.workspaceId, inbox, 20)
        delta(tenant.workspaceId, null, 300) // a cascaded attachment delete: workspace only
        reservation(tenant.workspaceId, inbox, 4_000, state = "RESERVED")
        reservation(tenant.workspaceId, inbox, 50_000, state = "RELEASING")

        val response = get("/v1/workspace/storage", tenant.apiKey)
        response.statusCode.value() shouldBe 200
        val usage = json.readTree(response.body).usage()
        usage.limit shouldBe workspaceLimit
        usage.stored shouldBe 1_320
        usage.reserved shouldBe 54_000
        usage.available shouldBe workspaceLimit - 1_320 - 54_000
        usage.overLimit shouldBe false
        // Charged as a READ (ADR-035 §13a), not left on the default or made free.
        response.headers.getFirst("RateLimit-Limit") shouldBe "600"
    }

    @Test
    fun `workspace storage at and over the limit - equality is not over, available is zero either way`() {
        val tenant = provisionIsolatedWorkspace("ws-full")
        val inbox = InboxId(UUID.fromString(createInbox(tenant.apiKey)["id"].asText()))
        workspaceBase(tenant.workspaceId, workspaceLimit - 10)
        reservation(tenant.workspaceId, inbox, 10)
        val atLimit = json.readTree(get("/v1/workspace/storage", tenant.apiKey).body).usage()
        atLimit.available shouldBe 0
        atLimit.overLimit shouldBe false

        delta(tenant.workspaceId, inbox, 1)
        val over = json.readTree(get("/v1/workspace/storage", tenant.apiKey).body).usage()
        over.stored shouldBe workspaceLimit - 9
        over.available shouldBe 0
        over.overLimit shouldBe true
    }

    @Test
    fun `workspace storage takes no workspace id from anywhere and never shows another tenant's figures`() {
        val mine = provisionIsolatedWorkspace("ws-mine")
        val theirs = provisionIsolatedWorkspace("ws-theirs")
        workspaceBase(theirs.workspaceId, 999_999)
        json.readTree(get("/v1/workspace/storage", mine.apiKey).body).usage().stored shouldBe 0
        // Query, path and header attempts to name a workspace are ignored or not routed.
        json.readTree(get("/v1/workspace/storage?workspaceId=${theirs.workspaceId.value}", mine.apiKey).body).usage().stored shouldBe 0
        get("/v1/workspace/${theirs.workspaceId.value}/storage", mine.apiKey).statusCode.value() shouldBe 404
        val headers = headers(mine.apiKey).apply { set("X-Workspace-Id", theirs.workspaceId.value.toString()) }
        val spoofed = rest.exchange(url("/v1/workspace/storage"), HttpMethod.GET, HttpEntity(null, headers), String::class.java)
        json.readTree(spoofed.body).usage().stored shouldBe 0
    }

    @Test
    fun `workspace storage requires messages read, and nothing else`() {
        get("/v1/workspace/storage", key = null).statusCode.value() shouldBe 401
        get("/v1/workspace/storage", readOnlyKey).statusCode.value() shouldBe 200
        val tenant = provisionIsolatedWorkspace("ws-scope")
        val writeOnly = mintKey(tenant.workspaceId, tenant.projectId, setOf(ApiScope.INBOXES_WRITE))
        val forbidden = get("/v1/workspace/storage", writeOnly)
        forbidden.statusCode.value() shouldBe 403
        json.readTree(forbidden.body)["type"].asText() shouldContain "missing-scope"
    }

    @Test
    fun `a created inbox carries zero storage, no refusal, and its limit is the effective inbox limit`() {
        val tenant = provisionIsolatedWorkspace("inbox-new")
        val inbox = createInbox(tenant.apiKey)
        val usage = inbox["storage"].usage()
        usage.limit shouldBe inboxLimit
        usage.stored shouldBe 0
        usage.reserved shouldBe 0
        usage.available shouldBe inboxLimit // min(inbox headroom, workspace headroom) with an empty workspace
        usage.overLimit shouldBe false
        inbox["storageRefusalCount"].asLong() shouldBe 0
        inbox["lastStorageRefusalAt"].isNull shouldBe true
        inbox["lastStorageRefusalReason"].isNull shouldBe true
        // POST /v1/inboxes still needs only inboxes:write.
        val writeOnly = mintKey(tenant.workspaceId, tenant.projectId, setOf(ApiScope.INBOXES_WRITE))
        post("/v1/inboxes", """{}""", writeOnly).statusCode.value() shouldBe 201
    }

    @Test
    fun `GET inbox - stored and reserved are the inbox's, available is the minimum headroom, overLimit is the inbox's own`() {
        val tenant = provisionIsolatedWorkspace("inbox-figures")
        val inbox = InboxId(UUID.fromString(createInbox(tenant.apiKey)["id"].asText()))
        // No inbox_storage base row yet: delta and reservations must still count.
        delta(tenant.workspaceId, inbox, 100)
        reservation(tenant.workspaceId, inbox, 400)
        var usage = json.readTree(get("/v1/inboxes/${inbox.value}", tenant.apiKey).body)["storage"].usage()
        usage.stored shouldBe 100
        usage.reserved shouldBe 400
        usage.available shouldBe inboxLimit - 500
        usage.overLimit shouldBe false

        // The workspace fills up (other inboxes' content): the inbox is not over ITS limit, but can admit nothing.
        workspaceBase(tenant.workspaceId, workspaceLimit)
        usage = json.readTree(get("/v1/inboxes/${inbox.value}", tenant.apiKey).body)["storage"].usage()
        usage.stored shouldBe 100
        usage.available shouldBe 0
        usage.overLimit shouldBe false

        // The inbox itself goes over its limit.
        inboxBase(tenant.workspaceId, inbox, inboxLimit)
        usage = json.readTree(get("/v1/inboxes/${inbox.value}", tenant.apiKey).body)["storage"].usage()
        usage.stored shouldBe inboxLimit + 100
        usage.available shouldBe 0
        usage.overLimit shouldBe true
        usage.limit shouldBe inboxLimit
    }

    @Test
    fun `the refusal members come from the real refusal record and name the closed-enum reason`() {
        val tenant = provisionIsolatedWorkspace("inbox-refusal")
        val inbox = InboxId(UUID.fromString(createInbox(tenant.apiKey)["id"].asText()))
        refuse(inbox, StorageRefusalReason.INBOX_LIMIT)
        guardedRefusals.refuse(tenant.workspaceId, inbox, StorageRefusalReason.SERVICE_CAPACITY)

        val body = json.readTree(get("/v1/inboxes/${inbox.value}", tenant.apiKey).body)
        body["storageRefusalCount"].asLong() shouldBe 2
        body["lastStorageRefusalReason"].asText() shouldBe "SERVICE_CAPACITY"
        body["lastStorageRefusalAt"].isNull shouldBe false
        // A refusal is a record, not bytes: the usage is unchanged.
        body["storage"].usage().stored shouldBe 0
    }

    @Test
    fun `an idempotent create replay keeps the original identity and reads the storage members live`() {
        val tenant = provisionIsolatedWorkspace("inbox-replay")
        val key = "replay-${UUID.randomUUID()}"

        fun create(): ResponseEntity<String> {
            val headers =
                HttpHeaders().apply {
                    setBearerAuth(tenant.apiKey)
                    set("Content-Type", "application/json")
                    set("Idempotency-Key", key)
                }
            return rest.exchange(url("/v1/inboxes"), HttpMethod.POST, HttpEntity("""{"ttlSeconds":600}""", headers), String::class.java)
        }
        val first = create()
        first.statusCode.value() shouldBe 201
        first.headers.getFirst("Idempotency-Replayed") shouldBe "false"
        val created = json.readTree(first.body)
        created["storageRefusalCount"].asLong() shouldBe 0
        created["storage"].usage().stored shouldBe 0
        val inbox = InboxId(UUID.fromString(created["id"].asText()))

        // The inbox's accounting and refusal state move on.
        delta(tenant.workspaceId, inbox, 1_234)
        reservation(tenant.workspaceId, inbox, 5)
        refuse(inbox, StorageRefusalReason.WORKSPACE_LIMIT)

        val replay = create()
        replay.statusCode.value() shouldBe 201
        replay.headers.getFirst("Idempotency-Replayed") shouldBe "true"
        val replayed = json.readTree(replay.body)
        replayed["id"].asText() shouldBe created["id"].asText()
        replayed["address"].asText() shouldBe created["address"].asText()
        replayed["createdAt"].asText() shouldBe created["createdAt"].asText()
        // Live, not frozen at creation.
        replayed["storage"].usage().stored shouldBe 1_234
        replayed["storage"].usage().reserved shouldBe 5
        replayed["storageRefusalCount"].asLong() shouldBe 1
        replayed["lastStorageRefusalReason"].asText() shouldBe "WORKSPACE_LIMIT"
    }

    @Test
    fun `a cross-tenant inbox is 404 with nothing about its storage or refusals`() {
        val tenant = provisionIsolatedWorkspace("inbox-foreign")
        val inbox = InboxId(UUID.fromString(createInbox(tenant.apiKey)["id"].asText()))
        refuse(inbox, StorageRefusalReason.INBOX_LIMIT)
        val response = get("/v1/inboxes/${inbox.value}", otherWorkspaceKey)
        response.statusCode.value() shouldBe 404
        response.body!! shouldContain "inbox-not-found"
        (response.body!!.contains("storage") || response.body!!.contains("INBOX_LIMIT")) shouldBe false
    }
}
