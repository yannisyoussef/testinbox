package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import java.util.UUID

/**
 * The V8 grant contract of `docs/dev/production.md`, run as the roles it
 * names (TI-STORAGE-006E). Staging connects as the table owner and never
 * sees any of this; a role-separated production does, the moment V8 commits:
 *
 * - the INGESTION and API roles' deletes run the debt triggers, which are
 *   SECURITY DEFINER, so they need no V8 grant (and cannot write debt rows
 *   themselves);
 * - the monitor role begins walks and writes observations, nothing else;
 * - the application role reads observations and never writes them.
 *
 * Each test states the privilege it proves, with the grant and without it,
 * and two replay the temp-schema shadowing attack against the definer code.
 */
class StorageV8GrantsTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private class Role(
        val name: String,
        val password: String = "pw-$name",
    )

    private fun role(prefix: String) = Role("${prefix}_${UUID.randomUUID().toString().take(8)}")

    private fun LedgerTestDatabase.createRole(role: Role) {
        jdbc.sql("CREATE ROLE ${role.name} LOGIN PASSWORD '${role.password}'").update()
        jdbc.sql("GRANT CONNECT ON DATABASE $name TO ${role.name}").update()
        jdbc.sql("GRANT USAGE ON SCHEMA public TO ${role.name}").update()
    }

    private fun LedgerTestDatabase.grant(
        role: Role,
        vararg statements: String,
    ) = statements.forEach { jdbc.sql("$it TO ${role.name}").update() }

    private fun LedgerTestDatabase.rawConnectionAs(role: Role): java.sql.Connection =
        java.sql.DriverManager.getConnection(
            postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
            role.name,
            role.password,
        )

    private fun LedgerTestDatabase.connectAs(role: Role): JdbcClient =
        JdbcClient.create(
            SimpleDriverDataSource(
                org.postgresql.Driver(),
                postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
                role.name,
                role.password,
            ),
        )

    private fun permissionDenied(block: () -> Unit): String {
        val failure = runCatching(block).exceptionOrNull() ?: error("expected permission denied")
        val message = generateSequence(failure as Throwable) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        message shouldContain "permission denied"
        return message
    }

    /** The reservation and row grants both deployables already hold today (production.md, TI-STORAGE-003). */
    private fun LedgerTestDatabase.preV8Grants(role: Role) =
        grant(
            role,
            "GRANT SELECT, INSERT, UPDATE, DELETE ON storage_reservation, storage_ambiguity, storage_node, storage_admission_latch, storage_clock_episode",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON workspace, project, inbox, message, attachment",
            "GRANT SELECT, INSERT, UPDATE, DELETE ON storage_delta, workspace_storage_account, inbox_storage",
            "GRANT USAGE ON storage_delta_id_seq, storage_ambiguity_id_seq",
            "GRANT SELECT ON exact_address_reservation",
        )

    @Test
    fun `the ingestion role's T2 consume needs no V8 grant - the debt trigger runs as its owner`() {
        // Before the review the trigger was SECURITY INVOKER, so a missing INSERT grant on
        // storage_deletion_debt failed EVERY T2 commit (a uniform 451, then silent loss),
        // and the grant let the internet-facing role write never-compactable pending rows.
        val db = LedgerTestDatabase.create(postgres, admin)
        val ingestion = role("ti_ingest")
        db.createRole(ingestion)
        db.preV8Grants(ingestion)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val reserved = seedReservation(db, ws, inbox)
        val asIngestion = db.connectAs(ingestion)

        asIngestion.sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RESERVED'").param(reserved).update() shouldBe 1
        db.debtRows().isEmpty() shouldBe true // consumed, not released: no debt
        permissionDenied {
            asIngestion
                .sql("INSERT INTO storage_deletion_debt (bytes, objects, incurred_at, object_key) VALUES (1, 1, 'infinity', 'k')")
                .update()
        }
    }

    @Test
    fun `the API role's retention delete needs no V8 grant, and still incurs the debt`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val api = role("ti_app")
        db.createRole(api)
        db.preV8Grants(api)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))

        db
            .connectAs(api)
            .sql("DELETE FROM inbox WHERE id = ?")
            .param(inbox)
            .update() shouldBe 1
        db.debtRows().map { it.first to it.second }.sortedBy { it.first } shouldBe listOf(10L to 1L, 100L to 1L)
    }

    @Test
    fun `the monitor role writes observations and can neither read nor delete them, the application role reads and never writes`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val monitor = role("ti_monitor")
        val api = role("ti_app")
        db.createRole(monitor)
        db.createRole(api)
        db.grant(
            monitor,
            "GRANT INSERT ON storage_filesystem_observation",
            "GRANT USAGE ON storage_filesystem_observation_id_seq",
            "GRANT EXECUTE ON FUNCTION storage_begin_observation()",
        )
        db.grant(
            api,
            "GRANT SELECT ON storage_filesystem_observation, storage_debt_watermark",
            "GRANT SELECT ON storage_deletion_debt",
            "GRANT SELECT, UPDATE ON storage_footprint_trust",
        )
        val asMonitor = db.connectAs(monitor)
        val asApi = db.connectAs(api)

        // The order and the start are issued by the server BEFORE measuring (contract §5.3).
        val started = asMonitor.sql("SELECT storage_begin_observation()").query(Long::class.java).single()
        asMonitor.observe(started, "ops-monitor") shouldBe 1
        permissionDenied { asMonitor.sql("SELECT count(*) FROM storage_filesystem_observation").query(Long::class.java).single() }
        permissionDenied { asMonitor.sql("DELETE FROM storage_filesystem_observation").update() }
        permissionDenied { asMonitor.sql("SELECT * FROM storage_observation_walk").query().listOfRows() }

        asApi.sql("SELECT count(*) FROM storage_filesystem_observation").query(Long::class.java).single() shouldBe 1
        permissionDenied { asApi.observe(started, "app") }
        permissionDenied { asApi.sql("SELECT storage_begin_observation()").query().listOfRows() }
        permissionDenied { asApi.sql("UPDATE storage_filesystem_observation SET trash_bytes = 0").update() }
        permissionDenied { asApi.sql("DELETE FROM storage_filesystem_observation").update() }
        // The reads the compactor makes are exactly what the API role holds.
        val ledger = JdbcStorageLedger(asApi, db.transactions)
        checkNotNull(ledger.deletionDebt().observation).let {
            it.source shouldBe "ops-monitor"
            it.writtenBy shouldBe monitor.name
        }
        // The API role deletes no debt row directly: only through the definer
        // function, which never deletes a pending row and raises the watermark.
        permissionDenied { asApi.sql("DELETE FROM storage_deletion_debt").update() }
        permissionDenied { ledger.compactDeletionDebt() }
        permissionDenied { asApi.sql("SELECT storage_resolve_pending_debt('k')").query().listOfRows() }
        db.grant(
            api,
            "GRANT EXECUTE ON FUNCTION storage_compact_deletion_debt(), storage_resolve_pending_debt(text), " +
                "storage_record_pending_debt(text, bigint, bigint, text)",
        )
        ledger.compactDeletionDebt() shouldBe 0
        permissionDenied { asMonitor.sql("SELECT storage_compact_deletion_debt()").query().listOfRows() }
        // Trust can be marked, never re-validated by lowering the epoch.
        asApi.sql("UPDATE storage_footprint_trust SET distrust_epoch = distrust_epoch + 1").update() shouldBe 1
        runCatching { asApi.sql("UPDATE storage_footprint_trust SET distrust_epoch = 0").update() }.isFailure shouldBe true
    }

    @Test
    fun `a temp table cannot hijack a definer function - the API role cannot compact real debt through a shadow observation`() {
        // Security review P1-1: without pg_temp LAST in search_path, PostgreSQL searches the
        // caller's temp schema FIRST, and a shadow storage_filesystem_observation holding
        // int8 max made storage_compact_deletion_debt() delete every resolved debt row.
        val db = LedgerTestDatabase.create(postgres, admin)
        val api = role("ti_app")
        db.createRole(api)
        db.grant(api, "GRANT EXECUTE ON FUNCTION storage_compact_deletion_debt()")
        db.jdbc.sql("GRANT TEMP ON DATABASE ${db.name} TO ${api.name}").update()
        repeat(5) { db.jdbc.sql("INSERT INTO storage_deletion_debt (bytes, objects) VALUES (100, 1)").update() }
        val compacted =
            JdbcClientSession(db.rawConnectionAs(api)).run {
                listOf(
                    "CREATE TEMP TABLE storage_filesystem_observation (started_seq bigint)",
                    "INSERT INTO storage_filesystem_observation VALUES (9223372036854775807)",
                    "CREATE TEMP TABLE storage_debt_watermark (id smallint, compacted_through_seq bigint)",
                    "INSERT INTO storage_debt_watermark VALUES (1, 0)",
                ).forEach(::execute)
                single("SELECT storage_compact_deletion_debt()")
            }

        compacted shouldBe 0 // the real table has no observation: nothing is superseded
        db.debtRows().size shouldBe 5
        db.jdbc
            .sql("SELECT compacted_through_seq FROM storage_debt_watermark")
            .query(Long::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `a temp table cannot hijack the observation trigger - the monitor cannot invent an order`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val monitor = role("ti_monitor")
        db.createRole(monitor)
        db.grant(monitor, "GRANT INSERT ON storage_filesystem_observation", "GRANT USAGE ON storage_filesystem_observation_id_seq")
        db.jdbc.sql("GRANT TEMP ON DATABASE ${db.name} TO ${monitor.name}").update()

        val failure =
            runCatching {
                JdbcClientSession(db.rawConnectionAs(monitor)).run {
                    execute("CREATE TEMP TABLE storage_observation_walk (started_seq bigint, started_at timestamptz, began_by name)")
                    execute("INSERT INTO storage_observation_walk VALUES (999999, clock_timestamp(), session_user)")
                    execute("CREATE TEMP TABLE storage_debt_order_seq (last_value bigint)")
                    execute("INSERT INTO storage_debt_order_seq VALUES (9223372036854775807)")
                    execute(OBSERVATION_INSERT.replace("?", "999999").replaceFirst("'ops-monitor'", "'shadow'"))
                }
            }
        failure.isFailure shouldBe true
        db.jdbc
            .sql("SELECT count(*) FROM storage_filesystem_observation")
            .query(Long::class.java)
            .single() shouldBe 0
    }

    /** One session, so that temp tables survive between statements. */
    private class JdbcClientSession(
        private val connection: java.sql.Connection,
    ) {
        fun execute(sql: String) {
            connection.createStatement().use { it.execute(sql) }
        }

        fun single(sql: String): Long =
            connection.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
    }

    private fun JdbcClient.observe(
        startedSeq: Long,
        source: String,
    ): Int = sql(OBSERVATION_INSERT.replaceFirst("'ops-monitor'", "'$source'")).param(startedSeq).update()

    private fun seedReservation(
        db: LedgerTestDatabase,
        workspace: UUID,
        inbox: UUID,
    ): UUID {
        val message = UUID.randomUUID()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                VALUES (?, ?, ?, ARRAY['k'], 10, 'RESERVED', now(), now() + interval '2 minutes', 'seed', ?)
                """.trimIndent(),
            ).params(message, workspace, inbox, UUID.randomUUID())
            .update()
        return message
    }

    private companion object {
        const val OBSERVATION_INSERT =
            "INSERT INTO storage_filesystem_observation (started_seq, source, block_size_bytes, capacity_bytes, used_bytes, " +
                "avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes) " +
                "VALUES (?, 'ops-monitor', 4096, 1, 1, 0, 1, 1, 0, 0)"
    }
}
