package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.io.File
import java.util.UUID

/**
 * The reference production role model (`deploy/database/roles.reference.sql`),
 * proven on PostgreSQL with V1–V10 (Ops finding 3, TI-STORAGE-006E).
 *
 * The test applies the reference grants as the schema owner, then:
 * - runs the deployables' storage operations as the roles it creates;
 * - shows that the writes the containment proof forbids are refused;
 * - runs activation gate F's own privilege query from
 *   `scripts/check-storage-activation.sh`, which must report **no**
 *   violation, and the monitor as the only observation writer.
 *
 * A blanket "DML on all tables", which an earlier Ops role script granted,
 * fails that last step.
 */
class StorageRoleModelTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private val suffix = UUID.randomUUID().toString().take(8)
    private val api = "ti_api_$suffix"
    private val ingestion = "ti_ingest_$suffix"
    private val monitor = "ti_monitor_$suffix"

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .first { it.isFile }

    private fun LedgerTestDatabase.login(role: String): JdbcClient {
        jdbc.sql("CREATE ROLE $role LOGIN PASSWORD 'pw-$role'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO $role").update()
        return JdbcClient.create(
            SimpleDriverDataSource(org.postgresql.Driver(), postgres.jdbcUrl.substringBeforeLast('/') + "/" + name, role, "pw-$role"),
        )
    }

    private fun LedgerTestDatabase.applyReferenceGrants() {
        val sql =
            repoFile("deploy/database/roles.reference.sql")
                .readLines()
                .filterNot { it.startsWith("\\") }
                .joinToString("\n")
                .replace(":\"api_role\"", "\"$api\"")
                .replace(":\"ingestion_role\"", "\"$ingestion\"")
                .replace(":\"monitor_role\"", "\"$monitor\"")
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /** Gate F's state statement, read from the script itself, for the given application roles. */
    private fun LedgerTestDatabase.gateFState(appRoles: String): String {
        val script = repoFile("scripts/check-storage-activation.sh").readText()
        val sql =
            script
                .substringAfter("FOOTPRINT_STATE_SQL=\"\$(cat <<'SQL'\n")
                .substringBefore("\nSQL\n)\"")
                .replace(":'app_roles'", "'$appRoles'")
        return jdbc.sql(sql).query(String::class.java).single()
    }

    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        return generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    @Test
    fun `the reference grants run every storage operation of the deployables, refuse the forbidden writes, and pass gate F`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val asApi = db.login(api)
        val asIngestion = db.login(ingestion)
        val asMonitor = db.login(monitor)
        // An earlier role script's leftovers - a blanket DML grant and a TRUNCATE - are cleared by the clean slate.
        db.jdbc.sql("GRANT USAGE ON SCHEMA public TO $api").update()
        db.jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public TO $api").update()
        db.applyReferenceGrants()
        db.applyReferenceGrants() // re-run after every migration

        // Ingestion: commits mail (the ledger and debt triggers run as their owner), registers
        // and heartbeats its node, records a refusal, and runs the probe pair.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(10), connection = asIngestion)
        val nodes = JdbcStorageAmbiguity(asIngestion, db.transactions)
        val generation = UUID.randomUUID()
        nodes.registerGeneration("ing-1", generation, "storage-v1")
        nodes.heartbeat("ing-1", generation, "storage-v1") shouldBe true // an existing generation is updated
        asIngestion
            .sql(
                "INSERT INTO inbox_storage (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason) " +
                    "VALUES (?, ?, 1, now(), 'INBOX_LIMIT') ON CONFLICT (inbox_id) DO UPDATE SET refusal_count = inbox_storage.refusal_count + 1",
            ).params(inbox, ws)
            .update() shouldBe 1
        asIngestion.sql("SELECT storage_record_probe_debt('_probe/ing-1/1')").query().listOfRows()
        asIngestion.sql("SELECT storage_resolve_probe_debt('_probe/ing-1/1')").query().listOfRows()

        // API: compaction, repair, the trust verifier, the debt functions and the sweep record.
        val ledger = JdbcStorageLedger(asApi, db.transactions)
        ledger.compact(100).foldedRows shouldBe 2
        ledger.repairDrift() shouldBe emptyList()
        ledger.confirmTrust() shouldBe true
        ledger.compactDeletionDebt()
        asApi.sql("SELECT storage_record_pending_debt('ws/in/m/raw.eml', 10, 1, 'test')").query().listOfRows()
        asApi.sql("SELECT storage_resolve_pending_debt('ws/in/m/raw.eml')").query().listOfRows()
        val runs = JdbcSweepRuns(asApi, "api-1")
        runs.complete(runs.begin(), 1_000)

        // Monitor: one observation, server-ordered.
        val walk = asMonitor.sql("SELECT storage_begin_observation()").query(Long::class.java).single()
        asMonitor
            .sql(
                "INSERT INTO storage_filesystem_observation (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, " +
                    "avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes) VALUES (?, 'monitor', 4096, 1, 0, 1, 1, 0, 0, 0)",
            ).param(walk)
            .update() shouldBe 1

        // The writes the containment proof forbids.
        for (role in listOf(asApi, asIngestion)) {
            failure {
                role
                    .sql(
                        "INSERT INTO storage_delta (workspace_id, bytes, objects) VALUES (?, -1, -1)",
                    ).param(ws)
                    .update()
            } shouldContain
                "permission denied"
            failure { role.sql("UPDATE workspace_storage_account SET base_objects = 0").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE inbox_storage SET base_bytes = 0").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE storage_footprint_trust SET trusted_epoch = 0").update() } shouldContain "permission denied"
            failure { role.sql("DELETE FROM storage_deletion_debt").update() } shouldContain "permission denied"
            failure { role.sql("TRUNCATE storage_reservation").update() } shouldContain "permission denied"
            failure { role.sql("SELECT setval('storage_debt_order_seq', 1)").query().listOfRows() } shouldContain "permission denied"
            failure { role.sql("SELECT storage_begin_observation()").query().listOfRows() } shouldContain "permission denied"
        }
        // The fail-closed latch: a deployable sets it, only an operator clears it.
        JdbcStorageAmbiguity(asIngestion, db.transactions).latch("role-model test")
        for (role in listOf(asApi, asIngestion)) {
            failure { role.sql("DELETE FROM storage_admission_latch").update() } shouldContain "permission denied"
            failure { role.sql("UPDATE storage_admission_latch SET reason = 'x'").update() } shouldContain "permission denied"
        }
        // The internet-facing role holds no general debt function and no ledger maintenance.
        failure { asIngestion.sql("SELECT storage_record_pending_debt('k', 1, 1, 'x')").query().listOfRows() } shouldContain
            "permission denied"
        failure { asIngestion.sql("SELECT storage_compact_ledger(1)").query().listOfRows() } shouldContain "permission denied"

        // Activation gate F's own privilege query: nothing to report.
        val state = db.gateFState("$api,$ingestion").replace(" ", "")
        state shouldContain "\"roleViolations\":[]"
        state shouldContain "\"observationInserters\":[\"$monitor\"]"
        state shouldContain "\"beginObservationExecutors\":[\"$monitor\"]"
    }

    @Test
    fun `a blanket DML grant on every table - the earlier Ops role script - is exactly what gate F refuses`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.login(api)
        db.jdbc.sql("GRANT USAGE ON SCHEMA public TO $api").update()
        db.jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $api").update()

        val state = db.gateFState(api).replace(" ", "")
        state shouldContain "$api:INSERTonstorage_delta".replace(" ", "")
        state shouldContain "$api:UPDATEonstorage_footprint_trust.trusted_epoch".replace(" ", "")
        state shouldContain "$api:INSERTonstorage_filesystem_observation".replace(" ", "")
    }
}
