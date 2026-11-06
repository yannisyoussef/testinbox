package email.testinbox.persistence

import email.testinbox.application.storage.AdmissionLocking
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID
import kotlin.random.Random

/**
 * Gate D, mode a corrections A1–A3 (owner decision 2026-11-06, #124): the new
 * T1 statement returns exactly what the accepted one returned.
 *
 * The OLD texts are fixtures (`admission-equivalence/`), byte for byte what the
 * adapter sent before A1–A3, pinned by SHA-256 below; the NEW texts are what the
 * adapter sends now. Both run, in ONE transaction (so one `now()`), against the
 * same fixed-seed random ledger state, and every output column of every row must
 * be identical. States are written with triggers and foreign keys off
 * (`session_replication_role = replica`), so they include states no writer
 * produces, not only reachable ones: an equivalence over every state covers the
 * reachable ones. They cover legacy, participant, overlapping and unshared
 * workspaces, workspaces with no row at all, pending and ordered debt with and
 * without charges, orphan charges, deltas (NULL inbox included), reservations, a
 * missing V24 global base row, no observation, and the partition guard's inputs
 * (plan, bound, uncharged shared debt). Mutants of the new text prove the states
 * exercise each of A1–A3's rewrites.
 */
class AdmissionStatementEquivalenceTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private lateinit var db: LedgerTestDatabase
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    private val unbound = JdbcClient.create(SimpleDriverDataSource())
    private val modeA = JdbcStorageAdmission(unbound, TransactionTemplate(), readsFootprint = true)
    private val partitioned =
        JdbcStorageAdmission(unbound, TransactionTemplate(), readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED)
    private val newGate = "WITH ${FootprintSql.CTES} SELECT ${FootprintSql.COLUMNS}"

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/admission-equivalence/$name")) { "missing fixture $name" }
            .use { it.readAllBytes().decodeToString() }

    private val oldModeA = fixture("t1-mode-a-9d06ab2.sql")
    private val oldPartitioned = fixture("t1-partitioned-658782c.sql")
    private val oldGate = fixture("footprint-gate-658782c.sql")

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `the fixtures are the texts before A1-A3, and the adapter now sends different ones`() {
        // Mode a's text as the adapter sent it at 9d06ab2: AdmissionStatementTextTest's pin until A1-A3.
        sha256(oldModeA) shouldBe "199482a15a80d0fdcf0f786fca3dfb268a7b982dd54b86733761fff9c326eaa0"
        // The partitioned T1 and the footprint gate (JdbcFootprintGate, JdbcRowFreeDebtStore) at 658782c.
        sha256(oldPartitioned) shouldBe "7c47a8790422eebb0b9bf596203ce53717f8e4ffc1d2acdbc8658e44f28021bc"
        sha256(oldGate) shouldBe "c5652a85cb4351ec7ec0796a67a58abbdbfeb1433a4f19a4725cea8587fbc341"
        modeA.snapshotStatement shouldNotBe oldModeA
        checkNotNull(partitioned.partitionedStatement) shouldNotBe oldPartitioned
        newGate shouldNotBe oldGate
    }

    /** A mutant of the new mode a text: the one rewrite it breaks, and the text with that rewrite undone wrongly. */
    private class Mutant(
        val name: String,
        val from: String,
        val to: String,
    )

    private val mutants =
        listOf(
            // A3: the unattributed share of the one debt pass.
            Mutant(
                "unattributed debt filter inverted",
                "FILTER (WHERE s.workspace_id IS NULL)",
                "FILTER (WHERE s.workspace_id IS NOT NULL)",
            ),
            // A3: a workspace with two allocations must not count its debt twice.
            Mutant(
                "debt joined to shares without DISTINCT",
                "(SELECT DISTINCT workspace_id FROM cp_shares)",
                "(SELECT workspace_id FROM cp_shares)",
            ),
            // A3: the unattributed pool also carries charges whose debt row is gone.
            Mutant("orphan charges dropped", "+ coalesce(sum(c.bytes),0) AS bytes", "+ 0 AS bytes"),
            // A1: the restriction must keep every share, legacy ones included.
            Mutant(
                "cohort usage restricted to participants",
                "IN (SELECT workspace_id FROM cp_shares) GROUP BY",
                "IN (SELECT workspace_id FROM participant_workspace) GROUP BY",
            ),
        )

    @Test
    fun `old and new statements agree on every column of every row, over random ledger states`() {
        val newModeA = modeA.snapshotStatement
        val newPartitioned = checkNotNull(partitioned.partitionedStatement)
        val mutated =
            mutants.associate { m ->
                withClue(m.name) { newModeA.contains(m.from) shouldBe true }
                m.name to newModeA.replace(m.from, m.to)
            }
        val detected = mutableSetOf<String>()
        val coverage = mutableMapOf<String, Int>()

        for (seed in 1..SEEDS) {
            val state = seedState(seed)
            val (workspaces, inboxes) = state.binds
            read { client ->
                fun run(text: String) = rows(client, text, workspaces, inboxes)

                val old = run(oldModeA)
                withClue("seed $seed, mode a") { run(newModeA) shouldBe old }
                withClue("seed $seed, partitioned") { run(newPartitioned) shouldBe run(oldPartitioned) }
                withClue("seed $seed, footprint gate") { rows(client, newGate) shouldBe rows(client, oldGate) }
                for ((name, text) in mutated) if (run(text) != old) detected += name

                val global = old.single { it["scope"] == "GLOBAL" }

                @Suppress("UNCHECKED_CAST")
                val cohort = global["fp_cohort"] as Map<String, Any?>

                fun count(case: String) = coverage.merge(case, 1, Int::plus)
                if ((cohort["unknownBytes"] as Number).toLong() > 0) count("unattributed debt or charges")
                if ((cohort["shares"] as List<*>).isNotEmpty()) count("shares")
                if (state.globalBaseMissing) count("missing global base row")
                if (state.overlap) count("a workspace with two allocations")
                if (state.observations == 0) count("no observation")
                val guard = run(newPartitioned).single { it["scope"] == "GLOBAL" }
                for (column in PartitionedT1Sql.GUARD_COLUMN_NAMES) count("$column=${guard[column]}")
            }
        }

        withClue("every mutant must be caught by some state, or the states do not exercise that rewrite") {
            (mutants.map { it.name } - detected).shouldBeEmpty()
        }
        val cases =
            listOf(
                "unattributed debt or charges",
                "shares",
                "missing global base row",
                "a workspace with two allocations",
                "no observation",
            )
        for (case in cases) {
            withClue(case) { (coverage[case] ?: 0) shouldBeGreaterThan 0 }
        }
        // The partition guard's inputs: each condition both holds and fails somewhere.
        for (column in PartitionedT1Sql.GUARD_COLUMN_NAMES) {
            withClue("$column=t") { (coverage["$column=t"] ?: 0) shouldBeGreaterThan 0 }
            withClue("$column=f") { (coverage["$column=f"] ?: 0) shouldBeGreaterThan 0 }
        }
    }

    /**
     * Gate D A1–A3 in the plan: the in-flight tables are read in full only by the
     * global sums. The generic plan (PREPARE under `force_generic_plan`, as T1 runs
     * it) of the old text on the V25 schema sequentially scans `storage_delta` four
     * times (`g_delta`, `cp_delta`, `ws_delta`, `ib_delta`) and `storage_reservation`
     * twice (`g_reserved`, `cp_reserved`); the new text on V26 once each (`g_delta`,
     * `g_reserved`), and reads the debt table fewer times. A diagnostic of the plan,
     * not of correctness (that is the test above).
     */
    @Test
    fun `the new statement scans each in-flight table once, where the old one scanned the deltas four times`() {
        largeLedger()
        val before = seqScans(oldModeA, withoutV26 = true)
        val after = seqScans(modeA.snapshotStatement, withoutV26 = false)
        withClue("before (old text, V25 schema): $before\nafter (new text, V26 schema): $after") {
            (before["storage_delta"] ?: 0) shouldBe 4
            (before["storage_reservation"] ?: 0) shouldBe 2
            (after["storage_delta"] ?: 0) shouldBe 1
            (after["storage_reservation"] ?: 0) shouldBe 1
            (after["storage_deletion_debt"] ?: 0) shouldBeLessThan (before["storage_deletion_debt"] ?: 0)
        }
    }

    // --- running ---------------------------------------------------------------------------------

    private fun <T> read(block: (JdbcClient) -> T): T {
        val template = TransactionTemplate(db.transactions.transactionManager!!)
        template.isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        return checkNotNull(template.execute { block(db.jdbc) })
    }

    private fun uuidArray(ids: List<UUID>): AbstractSqlTypeValue =
        object : AbstractSqlTypeValue() {
            override fun createTypeValue(
                connection: Connection,
                sqlType: Int,
                typeName: String?,
            ): Any = connection.createArrayOf("uuid", ids.toTypedArray())
        }

    private fun rows(
        client: JdbcClient,
        text: String,
        workspaces: List<UUID>? = null,
        inboxes: List<UUID>? = null,
    ): List<Map<String, Any?>> {
        var spec = client.sql(text)
        if (workspaces != null) spec = spec.param("workspaces", uuidArray(workspaces), Types.ARRAY)
        if (inboxes != null) spec = spec.param("inboxes", uuidArray(inboxes), Types.ARRAY)
        return spec.query { rs, _ -> row(rs) }.list().sortedBy { it.toString() }
    }

    /** Every column as the driver renders it; the cohort JSON parsed, its `shares` array as a multiset (it has no ORDER BY). */
    private fun row(rs: ResultSet): Map<String, Any?> {
        val meta = rs.metaData
        return (1..meta.columnCount).associate { i ->
            val name = meta.getColumnLabel(i)
            val text = rs.getString(i)
            name to if (name == "fp_cohort" && text != null) cohort(text) else text
        }
    }

    private fun cohort(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val map = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return map + ("shares" to (map["shares"] as List<*>).sortedBy { it.toString() })
    }

    /** Sequential scans per table in [statement]'s generic plan; [withoutV26] plans it with V26's indexes dropped (rolled back). */
    private fun seqScans(
        statement: String,
        withoutV26: Boolean,
    ): Map<String, Int> {
        val text = statement.replace(":workspaces", "$1").replace(":inboxes", "$2")
        val plan =
            checkNotNull(
                db.transactions.execute { status ->
                    status.setRollbackOnly()
                    val client = db.jdbc
                    if (withoutV26) client.sql("DROP INDEX ix_storage_delta_workspace, ix_storage_delta_inbox").update()
                    client.sql("SET LOCAL plan_cache_mode = force_generic_plan").update()
                    client.sql("PREPARE t1_plan(uuid[], uuid[]) AS $text").update()
                    try {
                        client
                            .sql("EXPLAIN (FORMAT JSON) EXECUTE t1_plan(NULL, NULL)")
                            .query(String::class.java)
                            .single()
                    } finally {
                        client.sql("DEALLOCATE t1_plan").update()
                    }
                },
            )
        val counts = mutableMapOf<String, Int>()

        fun walk(node: JsonNode) {
            fun field(name: String) = node.get(name)?.toString()?.trim('"')
            if (field("Node Type") == "Seq Scan") counts.merge(checkNotNull(field("Relation Name")), 1, Int::plus)
            node.get("Plans")?.forEach { walk(it) }
        }
        walk(mapper.readTree(plan)[0]["Plan"])
        return counts
    }

    // --- states ----------------------------------------------------------------------------------

    private class State(
        val binds: Pair<List<UUID>, List<UUID>>,
        val globalBaseMissing: Boolean,
        val overlap: Boolean,
        val observations: Int,
    )

    private val tables =
        listOf(
            "storage_delta",
            "storage_reservation",
            "storage_deletion_debt",
            "workspace_deletion_charge",
            "workspace_storage_account",
            "inbox_storage",
            "storage_global_base",
            "participant_workspace",
            "storage_legacy_share",
            "storage_cohort_plan",
            "storage_partition_bound",
            "storage_filesystem_observation",
            "inbox",
            "workspace",
        )

    private fun hex(random: Random) = (1..64).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")

    /** One random state, written with triggers and foreign keys off; the binds T1 would be called with. */
    private fun seedState(seed: Int): State =
        checkNotNull(
            db.transactions.execute {
                db.jdbc.sql("SET LOCAL session_replication_role = replica").update()
                for (table in tables) db.jdbc.sql("DELETE FROM $table").update()
                Seeder(seed, db.jdbc).seed()
            },
        )

    /** The writes of one random state, in [seed]'s fixed order. */
    private inner class Seeder(
        private val seed: Int,
        private val j: JdbcClient,
    ) {
        private val random = Random(seed)
        private val workspaces = List(random.nextInt(2, 24)) { UUID.randomUUID() }

        // A workspace with deltas, reservations or debt and no workspace row (deleted, or never seen).
        private val ghosts = List(2) { UUID.randomUUID() }
        private val anyWorkspace = workspaces + ghosts
        private val shuffled = workspaces.shuffled(random)
        private val participants = shuffled.take(random.nextInt(0, minOf(30, workspaces.size) + 1) / 2)
        private val legacy = shuffled.drop(participants.size).take(random.nextInt(0, workspaces.size - participants.size + 1) / 2)
        private val shared = participants + legacy

        // Every fifth state: one workspace with both allocations (CohortSql.read refuses it; the SQL must still agree).
        private val overlap = seed % 5 == 0 && participants.isNotEmpty()

        // Inboxes: most in the inbox table, some only in the ledger.
        private val inboxes = anyWorkspace.associateWith { List(random.nextInt(0, 4)) { UUID.randomUUID() } }

        private fun pick(list: List<UUID>) = list[random.nextInt(list.size)]

        private fun someWorkspace() = if (shared.isNotEmpty() && random.nextBoolean()) pick(shared) else pick(anyWorkspace)

        fun seed(): State {
            tenants()
            shares()
            bases()
            val globalBaseMissing = globalBase()
            deltas()
            reservations()
            val observations = observations()
            charges(debts())
            guardInputs()
            return State(binds(), globalBaseMissing, overlap, observations)
        }

        private fun tenants() {
            for (ws in workspaces) j.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'w', now())").param(ws).update()
            for ((ws, ids) in inboxes) {
                for (id in ids.filter { ws !in ghosts && random.nextInt(5) > 0 }) {
                    j
                        .sql(
                            "INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at) " +
                                "VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')",
                        ).params(id, ws, ws, "$id@equivalence.test")
                        .update()
                }
            }
        }

        private fun shares() {
            participants.forEachIndexed { place, ws ->
                j
                    .sql(
                        "INSERT INTO participant_workspace (workspace_id, cohort_id, place, allocated_at) " +
                            "VALUES (?, 'beta-30', ?, now())",
                    ).params(ws, place + 1)
                    .update()
            }
            for (ws in legacy + listOfNotNull(participants.firstOrNull()?.takeIf { overlap })) {
                j
                    .sql("INSERT INTO storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) VALUES (?, ?, ?)")
                    .params(ws, random.nextLong(1, 1L shl 30), random.nextLong(1, 1L shl 30))
                    .update()
            }
        }

        private fun bases() {
            for (ws in workspaces.filter { random.nextInt(10) < 7 }) {
                j
                    .sql("INSERT INTO workspace_storage_account (workspace_id, base_bytes, base_objects) VALUES (?, ?, ?)")
                    .params(ws, random.nextLong(0, 1_000_000), random.nextLong(0, 100))
                    .update()
            }
            for ((ws, ids) in inboxes) {
                for (id in ids.filter { random.nextInt(10) < 6 }) {
                    j
                        .sql("INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes, base_objects) VALUES (?, ?, ?, ?)")
                        .params(id, ws, random.nextLong(0, 100_000), random.nextLong(0, 50))
                        .update()
                }
            }
        }

        /** The V24 row: missing in a quarter of the states (a restore), otherwise any value. True when missing. */
        private fun globalBase(): Boolean {
            if (random.nextInt(4) == 0) return true
            j
                .sql("INSERT INTO storage_global_base (id, base_bytes, base_objects) VALUES (1, ?, ?)")
                .params(random.nextLong(0, 10_000_000), random.nextLong(0, 1_000))
                .update()
            return false
        }

        private fun deltas() =
            repeat(random.nextInt(0, 80)) {
                val ws = someWorkspace()
                val inbox = inboxes.getValue(ws).takeIf { it.isNotEmpty() && random.nextInt(5) > 0 }?.let(::pick)
                j
                    .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects) VALUES (?, ?, ?, ?)")
                    .params(listOf(ws, inbox, random.nextLong(-5_000, 20_000), random.nextLong(-3, 10)))
                    .update()
            }

        private fun reservations() =
            repeat(random.nextInt(0, 40)) {
                val ws = someWorkspace()
                val inbox = inboxes.getValue(ws).takeIf { it.isNotEmpty() }?.let(::pick) ?: UUID.randomUUID()
                j
                    .sql(
                        """
                        INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                         write_deadline_at, node_id, generation)
                        VALUES (?, ?, ?, string_to_array(?, ','), ?, ?, now(), now() + interval '1 minute', 'n', ?)
                        """.trimIndent(),
                    ).params(
                        UUID.randomUUID(),
                        ws,
                        inbox,
                        List(random.nextInt(1, 5)) { UUID.randomUUID() }.joinToString(","),
                        random.nextLong(1, 100_000),
                        if (random.nextBoolean()) "RESERVED" else "RELEASING",
                        UUID.randomUUID(),
                    ).update()
            }

        /** None, one or two; the newest decides which ordered debt is superseded. */
        private fun observations(): Int {
            val count = random.nextInt(0, 3)
            for (seq in List(count) { random.nextLong(1, 200) }.distinct()) {
                j
                    .sql(
                        """
                        INSERT INTO storage_filesystem_observation (started_seq, started_at, observed_at, source, block_size_bytes,
                            capacity_bytes, used_bytes, avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                        VALUES (?, now(), now(), 'equivalence', 4096, 1000000, 0, 0, 0, 0, ?, 0)
                        """.trimIndent(),
                    ).params(seq, random.nextLong(0, 1000))
                    .update()
            }
            return count
        }

        /** Pending ('infinity') or ordered by seq; of no workspace, a shared, an unshared or a ghost one. */
        private fun debts(): List<Pair<Long, UUID?>> =
            List(random.nextInt(0, 50) + if (overlap) 1 else 0) { n ->
                val ws =
                    when {
                        overlap && n == 0 -> participants.first()
                        random.nextInt(4) == 0 -> null
                        else -> someWorkspace()
                    }
                val pending = random.nextInt(10) < 3
                val id =
                    j
                        .sql(
                            """
                            INSERT INTO storage_deletion_debt (bytes, objects, incurred_at, seq, object_key, source, workspace_id)
                            VALUES (?, ?, CASE WHEN ? THEN 'infinity'::timestamptz ELSE now() END, ?, ?, 'equivalence', ?)
                            RETURNING id
                            """.trimIndent(),
                        ).params(
                            listOf(
                                random.nextLong(0, 50_000),
                                random.nextLong(0, 20),
                                pending,
                                random.nextLong(1, 200),
                                if (pending || random.nextBoolean()) UUID.randomUUID().toString() else null,
                                ws,
                            ),
                        ).query(Long::class.java)
                        .single()
                id to ws
            }

        /** Of an existing debt row (its workspace or another) or of a gone one (an orphan). */
        private fun charges(debts: List<Pair<Long, UUID?>>) =
            repeat(random.nextInt(0, 30)) {
                val debt = debts.takeIf { it.isNotEmpty() && random.nextBoolean() }?.let { it[random.nextInt(it.size)] }
                val ws = debt?.second?.takeIf { random.nextBoolean() } ?: pick(anyWorkspace)
                j
                    .sql(
                        "INSERT INTO workspace_deletion_charge (debt_id, workspace_id, bytes, objects, seq, incurred_at) " +
                            "VALUES (?, ?, ?, ?, ?, now()) ON CONFLICT (debt_id) DO NOTHING",
                    ).params(debt?.first ?: random.nextLong(100_000, 200_000), ws, random.nextLong(0, 50_000), random.nextLong(0, 20), 1)
                    .update()
            }

        /** The partition guard's inputs: a plan, approved or not, and a bound that matches it or not. */
        private fun guardInputs() {
            if (random.nextInt(3) == 0) return
            val binding = hex(random)
            val fingerprint = hex(random)
            j
                .sql(
                    """
                    INSERT INTO storage_cohort_plan (approved, binding, fingerprint, unattributed_allowance, trash_allowance, monitor_role,
                        block_size_bytes, capacity_bytes, observation_max_age_ms, expected_nodes)
                    VALUES (?, ?, ?, ?, ?, 'monitor', 4096, 1000000000, 60000, ARRAY['api', 'smtp'])
                    """.trimIndent(),
                ).params(random.nextInt(4) > 0, binding, fingerprint, random.nextLong(0, 1_000_000), random.nextLong(0, 1_000_000))
                .update()
            if (random.nextInt(4) == 0) return
            val matches = random.nextInt(3) > 0
            val capacity = if (random.nextBoolean()) Long.MAX_VALUE / 2 else 1L
            j
                .sql(
                    "INSERT INTO storage_partition_bound " +
                        "(binding, fingerprint, payload_capacity_bytes, footprint_capacity_bytes) VALUES (?, ?, ?, ?)",
                ).params(if (matches) binding else hex(random), fingerprint, capacity, capacity)
                .update()
        }

        /** T1's binds: some known workspaces and inboxes, sometimes an unknown id, sometimes none. */
        private fun binds(): Pair<List<UUID>, List<UUID>> {
            val boundWorkspaces =
                if (random.nextInt(8) == 0) {
                    emptyList()
                } else {
                    val preferShared = shared.isNotEmpty() && random.nextBoolean()
                    List(random.nextInt(1, 6)) { if (preferShared) pick(shared) else pick(anyWorkspace) } +
                        listOfNotNull(UUID.randomUUID().takeIf { random.nextInt(4) == 0 })
                }
            val allInboxes = inboxes.values.flatten()
            val boundInboxes =
                List(if (allInboxes.isEmpty()) 0 else random.nextInt(0, 6)) { pick(allInboxes) } +
                    listOfNotNull(UUID.randomUUID().takeIf { random.nextInt(4) == 0 })
            return boundWorkspaces to boundInboxes
        }
    }

    /** A ledger large enough that the planner prefers an index over a scan wherever one serves (the plan diagnostic). */
    private fun largeLedger() {
        db.transactions.execute {
            val j = db.jdbc
            j.sql("SET LOCAL session_replication_role = replica").update()
            j
                .sql(
                    "INSERT INTO workspace (id, name, created_at) SELECT gen_random_uuid(), 'w', now() FROM generate_series(1, 1000)",
                ).update()
            j
                .sql(
                    "INSERT INTO storage_legacy_share (workspace_id, payload_limit_bytes, footprint_limit_bytes) " +
                        "SELECT id, 1000, 1000 FROM workspace ORDER BY id LIMIT 10",
                ).update()
            j
                .sql(
                    """
                    INSERT INTO storage_delta (workspace_id, inbox_id, bytes, objects)
                    SELECT w.id, gen_random_uuid(), 10, 1 FROM workspace w, generate_series(1, 40)
                    """.trimIndent(),
                ).update()
            j
                .sql(
                    """
                    INSERT INTO storage_reservation (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at,
                                                     write_deadline_at, node_id, generation)
                    SELECT gen_random_uuid(), w.id, gen_random_uuid(), ARRAY['k1', 'k2'], 10, 'RESERVED', now(), now(), 'n', gen_random_uuid()
                      FROM workspace w, generate_series(1, 20)
                    """.trimIndent(),
                ).update()
        }
        db.jdbc.sql("VACUUM ANALYZE").update()
    }

    private companion object {
        const val SEEDS = 120
    }
}
