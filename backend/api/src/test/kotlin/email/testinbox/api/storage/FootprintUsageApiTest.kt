package email.testinbox.api.storage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import email.testinbox.api.ApiIntegrationTestBase
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.persistence.JdbcFootprintGate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

/**
 * TI-DELIVERY-002b acceptance item 4: the private storage usage over HTTP with
 * a POPULATED `footprint` object, through the real API on real PostgreSQL.
 *
 * The deployment declares its filesystem (the footprint model is then known)
 * while enforcement stays `OFF`, exactly as an `OFF` node with complete
 * declarations runs; a workspace with an explicit legacy share then shows its
 * own physical charge. After an inbox is deleted and the sweep tears it down,
 * the charge stays as pending cleanup (V20), counted ONCE in `usedBytes`, and
 * the figures equal what T1's own snapshot (`JdbcFootprintGate`, the
 * `CohortSql` loads) charges that workspace. No global figure is serialized,
 * and another tenant sees nothing of it.
 */
@TestPropertySource(
    properties = [
        "testinbox.storage.filesystem.block-size-bytes=4096",
        "testinbox.storage.filesystem.object-overhead-max-bytes=24576",
        "testinbox.storage.filesystem.global-footprint-limit-bytes=21474836480",
        "testinbox.storage.filesystem.deletion-debt-budget-bytes=8589934592",
        "testinbox.storage.filesystem.metadata-budget-bytes=268435456",
        "testinbox.storage.filesystem.operational-reserve-bytes=3221225472",
        "testinbox.storage.filesystem.capacity-bytes=51539607552",
        "testinbox.storage.filesystem.inodes=12582912",
        "testinbox.storage.filesystem.observation-max-age=15m",
        "testinbox.storage.filesystem.probe-budget-bytes=67108864",
        "testinbox.storage.filesystem.monitor-role=testinbox_monitor",
    ],
)
class FootprintUsageApiTest : ApiIntegrationTestBase() {
    private val json = jacksonObjectMapper()
    private val model = FootprintModel(4096, 24576)
    private val payloadShare = 1L shl 20
    private val footprintShare = 2L shl 20

    @Autowired lateinit var jdbc: JdbcClient

    private val footprintMembers = listOf("availableBytes", "enforced", "limitBytes", "overLimit", "pendingCleanupBytes", "usedBytes")
    private val forbiddenFragments =
        listOf(
            "global",
            "finalize",
            "admissioncap",
            "node",
            "generation",
            "backlog",
            "ambigu",
            "capacity",
            "pool",
            "cohort",
            "legacy",
            "trash",
        )

    private fun names(node: JsonNode): List<String> =
        if (node.isObject) {
            node.properties().flatMap { (name, child) -> listOf(name) + names(child) }
        } else {
            node.flatMap { names(it) }
        }

    private fun storage(key: String): JsonNode {
        val response = get("/v1/workspace/storage", key)
        response.statusCode.value() shouldBe 200
        return json.readTree(response.body)
    }

    /** T1's own figure for the workspace: the same CTEs admission reads, never the visibility query. */
    private fun t1Charge(workspace: WorkspaceId): Long {
        val load =
            checkNotNull(
                JdbcFootprintGate(jdbc)
                    .observe()
                    ?.cohort
                    ?.loads
                    ?.get(workspace),
            ) { "no T1 load for the workspace" }
        return load.budget.charged(load.usage, model)
    }

    @Test
    fun `footprint is populated, counts pending cleanup once after an inbox deletion, matches T1, and leaks nothing global`() {
        val tenant = provisionIsolatedWorkspace("footprint")
        jdbc
            .sql("INSERT INTO storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) VALUES (?, ?, ?)")
            .params(tenant.workspaceId.value, payloadShare, footprintShare)
            .update()
        val created = post("/v1/inboxes", """{}""", tenant.apiKey)
        created.statusCode.value() shouldBe 201
        val inboxNode = json.readTree(created.body)
        val inbox = InboxId(UUID.fromString(inboxNode["id"].asText()))
        repeat(3) { appendVisibleMessage(inbox, inboxNode["address"].asText(), workspaceId = tenant.workspaceId) }
        val liveCharge = model.bound(15, 3) // three 5-byte raw objects

        val live = storage(tenant.apiKey)
        live["storedBytes"].asLong() shouldBe 15
        live["limitBytes"].asLong() shouldBe payloadShare // the smaller persisted share, as T1 applies it
        val fp = live["footprint"]
        fp.properties().map { it.key }.sorted() shouldBe footprintMembers
        fp["limitBytes"].asLong() shouldBe footprintShare
        fp["usedBytes"].asLong() shouldBe liveCharge
        fp["pendingCleanupBytes"].asLong() shouldBe 0
        fp["availableBytes"].asLong() shouldBe footprintShare - liveCharge
        fp["overLimit"].asBoolean() shouldBe false
        fp["enforced"].asBoolean() shouldBe false // OFF: reported, never enforced
        t1Charge(tenant.workspaceId) shouldBe liveCharge

        delete("/v1/inboxes/${inbox.value}", tenant.apiKey).statusCode.value() shouldBe 204
        await().atMost(Duration.ofSeconds(30)).until {
            jdbc
                .sql("SELECT count(*) FROM inbox WHERE id = ?")
                .param(inbox.value)
                .query(Long::class.java)
                .single() == 0L
        }

        val after = storage(tenant.apiKey)
        after["storedBytes"].asLong() shouldBe 0
        after["reservedBytes"].asLong() shouldBe 0
        val cleanup = after["footprint"]
        // Deleting rows does not free the physical share: the same charge, now as cleanup, counted once.
        cleanup["pendingCleanupBytes"].asLong() shouldBe liveCharge
        cleanup["usedBytes"].asLong() shouldBe liveCharge
        cleanup["availableBytes"].asLong() shouldBe footprintShare - liveCharge
        t1Charge(tenant.workspaceId) shouldBe cleanup["usedBytes"].asLong()

        // Nothing global, in either response.
        for (body in listOf(live, after)) {
            names(body).filter { name -> forbiddenFragments.any { name.lowercase().contains(it) } }.shouldBeEmpty()
        }
        // Another tenant sees neither the share nor the cleanup.
        val other = storage(provisionIsolatedWorkspace("footprint-other").apiKey)
        other.has("footprint") shouldBe false
        other["storedBytes"].asLong() shouldBe 0
    }
}
