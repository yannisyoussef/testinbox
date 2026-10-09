package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.util.UUID

/**
 * V10 (TI-STORAGE-006E owner review b, §2 and §3) on PostgreSQL.
 *
 * - Trust is established only by `storage_confirm_footprint_trust()`. That
 *   function verifies every workspace's bytes and object counts against the
 *   authoritative rows under the ledger lock, and marks the epoch it read
 *   compare-and-set. The first mark of an epoch is stamped with an order and a
 *   time.
 * - An application role cannot forge trust. With the documented grants it has
 *   no privilege on the trusted columns. Even with a legacy full `UPDATE`
 *   grant, the guard trigger refuses, and T1 keeps reading the counts as
 *   untrusted.
 * - The upgrade to V10 is a distrust event (fail closed after a roll-forward).
 * - Each full orphan sweep is recorded with a database-issued order and
 *   instants, and the covered figure is computed by the database.
 */
class StorageVerifiedTrustTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private class Role(
        val name: String,
        val password: String = "pw-$name",
    )

    private fun role(prefix: String) = Role("${prefix}_${UUID.randomUUID().toString().take(8)}")

    private fun LedgerTestDatabase.connectAs(role: Role): JdbcClient {
        jdbc.sql("CREATE ROLE ${role.name} LOGIN PASSWORD '${role.password}'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO ${role.name}").update()
        jdbc.sql("GRANT USAGE ON SCHEMA public TO ${role.name}").update()
        return JdbcClient.create(
            SimpleDriverDataSource(
                org.postgresql.Driver(),
                postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
                role.name,
                role.password,
            ),
        )
    }

    private fun LedgerTestDatabase.grant(
        role: Role,
        vararg statements: String,
    ) = statements.forEach { jdbc.sql("$it TO ${role.name}").update() }

    private fun failure(block: () -> Unit): String {
        val e = runCatching(block).exceptionOrNull() ?: error("expected a refusal")
        return generateSequence(e) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private fun LedgerTestDatabase.confirm(): Boolean =
        jdbc.sql("SELECT storage_confirm_footprint_trust()").query(Boolean::class.java).single()

    private fun LedgerTestDatabase.trustRow(): Map<String, Any?> =
        jdbc
            .sql(
                "SELECT distrust_epoch, trusted_epoch, trusted_seq, trusted_at FROM storage_footprint_trust WHERE id = 1",
            ).query()
            .singleRow()

    private fun LedgerTestDatabase.countsTrusted(): Boolean = checkNotNull(JdbcFootprintGate(jdbc).observe()).countsTrusted

    @Test
    fun `the verifier marks clean counts trusted once, stamping the order and time of the transition only`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        db.message(ws, db.inbox(ws), rawBytes = 1_000, attachments = listOf(10))
        db.countsTrusted() shouldBe false // V10's upgrade is itself a distrust event

        db.confirm() shouldBe true
        val first = db.trustRow()
        first["trusted_epoch"] shouldBe first["distrust_epoch"]
        first["trusted_seq"] shouldNotBe null
        first["trusted_at"] shouldNotBe null
        db.countsTrusted() shouldBe true

        db.confirm() shouldBe true
        db.trustRow()["trusted_seq"] shouldBe first["trusted_seq"] // re-confirming keeps the transition's order

        db.jdbc.sql("UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1").update()
        db.countsTrusted() shouldBe false
        db.confirm() shouldBe true
        (db.trustRow()["trusted_seq"] as Long) shouldNotBe first["trusted_seq"]
    }

    @Test
    fun `object-count drift against the authoritative rows refuses the mark - bytes alone are not enough`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        db.message(ws, db.inbox(ws), rawBytes = 1_000)
        db.ledger.compact(100)
        // Same bytes, one object too few: the undercount a pre-V8 compactor leaves.
        db.jdbc
            .sql(
                "UPDATE workspace_storage_account SET base_objects = base_objects - 1 WHERE workspace_id = ?",
            ).param(ws)
            .update() shouldBe
            1

        db.confirm() shouldBe false
        db.trustRow()["trusted_seq"] shouldBe null
        db.countsTrusted() shouldBe false
    }

    @Test
    fun `the verifier refuses to run under REPEATABLE READ, where its checks could predate the lock`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val message =
            failure {
                db.transactions.execute {
                    db.jdbc.sql("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ").update()
                    db.confirm()
                }
            }
        message shouldContain "READ COMMITTED only"
    }

    @Test
    fun `forged trust cannot authorize footprint admission - no grant, a legacy grant, or the owner without the verifier`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        db.message(ws, db.inbox(ws), rawBytes = 1_000)
        db.ledger.compact(100)
        db.jdbc
            .sql("UPDATE workspace_storage_account SET base_objects = 0 WHERE workspace_id = ?")
            .param(ws)
            .update()
        val forge = "UPDATE storage_footprint_trust SET trusted_epoch = distrust_epoch WHERE id = 1"

        // The documented grants: read, raise distrust, execute the verifier.
        val app = role("ti_app")
        val asApp = db.connectAs(app)
        db.grant(
            app,
            "GRANT SELECT, UPDATE (distrust_epoch) ON storage_footprint_trust",
            "GRANT EXECUTE ON FUNCTION storage_confirm_footprint_trust()",
        )
        failure { asApp.sql(forge).update() } shouldContain "permission denied"
        asApp.sql("SELECT storage_confirm_footprint_trust()").query(Boolean::class.java).single() shouldBe false // the drift is real
        asApp.sql("UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1 WHERE id = 1").update() shouldBe 1

        // A legacy full UPDATE grant: the guard trigger refuses.
        val legacy = role("ti_legacy")
        val asLegacy = db.connectAs(legacy)
        db.grant(legacy, "GRANT SELECT, INSERT, UPDATE ON storage_footprint_trust")
        failure { asLegacy.sql(forge).update() } shouldContain "trust is marked only by storage_confirm_footprint_trust()"
        // Even naming the verifier's marker: the current user is not the owner.
        failure {
            asLegacy
                .sql(
                    "WITH g AS (SELECT set_config('testinbox.trust_verifier', 'v10', true)) $forge AND EXISTS (SELECT 1 FROM g)",
                ).update()
        } shouldContain "trust is marked only by storage_confirm_footprint_trust()"

        // The owner (here the test's admin) without the verifier's marker: refused too.
        failure { db.jdbc.sql(forge).update() } shouldContain "trust is marked only by storage_confirm_footprint_trust()"

        db.countsTrusted() shouldBe false
        db.trustRow()["trusted_seq"] shouldBe null
    }

    @Test
    fun `with the documented grants the application compacts, repairs and confirms, and can no longer forge the counts it vouches for`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        db.message(ws, db.inbox(ws), rawBytes = 1_000, attachments = listOf(10))
        val api = role("ti_api")
        val asApi = db.connectAs(api)
        db.grant(
            api,
            "GRANT SELECT ON storage_delta, workspace_storage_account, inbox_storage, workspace, inbox, message, attachment",
            "GRANT INSERT (inbox_id, workspace_id, refusal_count, last_refusal_at, last_refusal_reason) ON inbox_storage",
            "GRANT UPDATE (refusal_count, last_refusal_at, last_refusal_reason) ON inbox_storage",
            "GRANT SELECT, UPDATE (distrust_epoch) ON storage_footprint_trust",
            "GRANT EXECUTE ON FUNCTION storage_compact_ledger(integer), storage_repair_ledger(), storage_confirm_footprint_trust()",
        )
        val ledger = JdbcStorageLedger(asApi, db.transactions)
        ledger.compact(100).foldedRows shouldBe 2
        ledger.repairDrift() shouldBe emptyList()
        ledger.confirmTrust() shouldBe true

        // The forgery the security review demonstrated: a negative delta after the mark.
        failure {
            asApi.sql("INSERT INTO storage_delta (workspace_id, bytes, objects) VALUES (?, -1000000, -5)").param(ws).update()
        } shouldContain "permission denied"
        failure { asApi.sql("UPDATE workspace_storage_account SET base_objects = 0").update() } shouldContain "permission denied"
        failure { asApi.sql("DELETE FROM storage_delta").update() } shouldContain "permission denied"
        failure { asApi.sql("UPDATE inbox_storage SET base_bytes = 0").update() } shouldContain "permission denied"
        // The refusal record still works through its column grant.
        asApi.sql("UPDATE inbox_storage SET refusal_count = refusal_count WHERE workspace_id = ?").param(ws).update()
        db.countsTrusted() shouldBe true
    }

    @Test
    fun `node rows below containment level 1 leave a durable order behind, even once they are reaped`() {
        val db = LedgerTestDatabase.create(postgres, admin)

        fun watermark() =
            db.jdbc
                .sql("SELECT last_lower_seq FROM storage_containment_watermark")
                .query(Long::class.java)
                .single()
        val atUpgrade = watermark()
        val generation = UUID.randomUUID()
        db.jdbc
            .sql("INSERT INTO storage_node (node_id, generation, capability, heartbeat_at) VALUES ('old', ?, 'storage-v1', now())")
            .param(generation)
            .update()
        val registered = watermark()
        (registered > atUpgrade) shouldBe true
        db.jdbc.sql("UPDATE storage_node SET heartbeat_at = now() WHERE node_id = 'old'").update()
        val heartbeat = watermark()
        (heartbeat > registered) shouldBe true
        db.jdbc.sql("DELETE FROM storage_node WHERE node_id = 'old'").update() // reaped
        (watermark() > heartbeat) shouldBe true

        // A level-1 node leaves it alone; nobody can lower it.
        val before = watermark()
        JdbcStorageAmbiguity(db.jdbc, db.transactions).registerGeneration("new", UUID.randomUUID(), "storage-v1")
        watermark() shouldBe before
        failure { db.jdbc.sql("UPDATE storage_containment_watermark SET last_lower_seq = 0").update() } shouldContain "only grows"
        failure { db.jdbc.sql("DELETE FROM storage_containment_watermark").update() } shouldContain "only grows"

        // Lost to a restore (it is not backed up): the next verification recreates it at the current order.
        db.jdbc.sql("TRUNCATE storage_containment_watermark").update()
        db.confirm() shouldBe true
        (watermark() > before) shouldBe true
    }

    @Test
    fun `a trust row lost to a restore comes back untrusted through the verifier, never through the application`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.jdbc.sql("DELETE FROM storage_footprint_trust").update()
        db.countsTrusted() shouldBe false
        db.confirm() shouldBe true // recreated untrusted at epoch 0, then verified and marked
        db.trustRow()["trusted_seq"] shouldNotBe null
    }

    @Test
    fun `the upgrade to V10 revokes trust marked before it, so a roll-forward fails closed until a verification`() {
        val db = LedgerTestDatabase.create(postgres, admin, target = "9")
        db.jdbc.sql("UPDATE storage_footprint_trust SET trusted_epoch = distrust_epoch WHERE id = 1").update() shouldBe 1
        db.countsTrusted() shouldBe true

        LedgerTestDatabase.flyway(db.dataSource, target = "10").migrate()
        db.countsTrusted() shouldBe false
        db.confirm() shouldBe true
        db.countsTrusted() shouldBe true
    }

    @Test
    fun `a sweep run carries a database-issued order and instants, the covered figure the database computes, and never changes`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ws = db.workspace()
        db.message(ws, db.inbox(ws), rawBytes = 1_000, attachments = listOf(10))
        val api = role("ti_api")
        val asApi = db.connectAs(api)
        db.grant(api, "GRANT EXECUTE ON FUNCTION storage_begin_sweep(text), storage_complete_sweep(bigint, text, bigint)")

        val runs = JdbcSweepRuns(asApi, "api-1")
        val run = runs.begin()
        runs.complete(run, listedBytes = 900)
        // Another node cannot complete this node's run.
        failure { JdbcSweepRuns(asApi, "api-2").complete(run, 0) } shouldContain "another node"
        val row =
            db.jdbc
                .sql("SELECT * FROM storage_sweep_run WHERE id = ?")
                .param(run)
                .query()
                .singleRow()
        row["node_id"] shouldBe "api-1"
        row["physical_listed_bytes"] shouldBe 900L
        row["covered_bytes"] shouldBe 1_010L
        (row["started_seq"] as Long) shouldNotBe 0L

        failure { runs.complete(run, 1) } shouldContain "already completed"
        failure {
            db.jdbc
                .sql("UPDATE storage_sweep_run SET physical_listed_bytes = 0 WHERE id = ?")
                .param(run)
                .update()
        } shouldContain
            "never changed"
        failure {
            asApi.sql("INSERT INTO storage_sweep_run (node_id, started_seq, started_at) VALUES ('x', 1, now())").update()
        } shouldContain "permission denied"
    }

    @Test
    fun `a node row an earlier artifact inserts carries containment 0, and this artifact's carries 1`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        db.jdbc
            .sql("INSERT INTO storage_node (node_id, generation, capability, heartbeat_at) VALUES ('old', ?, 'storage-v1', now())")
            .param(UUID.randomUUID())
            .update()
        JdbcStorageAmbiguity(db.jdbc, db.transactions).registerGeneration("new", UUID.randomUUID(), "storage-v1")
        db.jdbc
            .sql("SELECT node_id, containment FROM storage_node ORDER BY node_id")
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
            .list() shouldBe listOf("new" to 1, "old" to 0)
    }
}
