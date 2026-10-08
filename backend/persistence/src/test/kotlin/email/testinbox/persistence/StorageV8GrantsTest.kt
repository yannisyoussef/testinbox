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
 * - the INGESTION role's T2 consume runs the new statement trigger on
 *   `storage_reservation`, which inserts nothing for a RESERVED row but still
 *   has its INSERT privilege on `storage_deletion_debt` checked;
 * - the API role's retention delete runs the ledger triggers, which do insert;
 * - the monitor role writes observations and nothing else;
 * - the application role reads observations and never writes them.
 *
 * Each test states the privilege it proves, with the grant and without it.
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
    fun `the ingestion role's T2 consume needs INSERT on storage_deletion_debt although it inserts no row`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val ingestion = role("ti_ingest")
        db.createRole(ingestion)
        db.preV8Grants(ingestion)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        val first = seedReservation(db, ws, inbox)
        val second = seedReservation(db, ws, inbox)
        val asIngestion = db.connectAs(ingestion)

        // Exactly JdbcStorageReservations.consume, without the V8 grant: every T2 commit would fail like this.
        permissionDenied {
            asIngestion.sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RESERVED'").param(first).update()
        } shouldContain "storage_deletion_debt"

        db.grant(ingestion, "GRANT INSERT ON storage_deletion_debt", "GRANT USAGE ON storage_deletion_debt_id_seq")
        asIngestion.sql("DELETE FROM storage_reservation WHERE message_id = ? AND state = 'RESERVED'").param(second).update() shouldBe 1
        db.debtRows().isEmpty() shouldBe true // consumed, not released: no debt
    }

    @Test
    fun `the API role's retention delete needs INSERT on storage_deletion_debt, and then incurs the debt`() {
        val db = LedgerTestDatabase.create(postgres, admin)
        val api = role("ti_app")
        db.createRole(api)
        db.preV8Grants(api)
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))
        val asApi = db.connectAs(api)

        permissionDenied { asApi.sql("DELETE FROM inbox WHERE id = ?").param(inbox).update() } shouldContain "storage_deletion_debt"
        db.jdbc
            .sql("SELECT count(*) FROM inbox WHERE id = ?")
            .param(inbox)
            .query(Long::class.java)
            .single() shouldBe 1

        db.grant(api, "GRANT INSERT ON storage_deletion_debt", "GRANT USAGE ON storage_deletion_debt_id_seq")
        // A debt row takes its order from the sequence (contract §5.3): without it the delete still fails.
        permissionDenied { asApi.sql("DELETE FROM inbox WHERE id = ?").param(inbox).update() } shouldContain "storage_debt_order_seq"
        db.grant(api, "GRANT USAGE ON storage_debt_order_seq")
        asApi.sql("DELETE FROM inbox WHERE id = ?").param(inbox).update() shouldBe 1
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
            "GRANT USAGE ON storage_filesystem_observation_id_seq, storage_debt_order_seq",
        )
        db.grant(
            api,
            "GRANT SELECT ON storage_filesystem_observation, storage_debt_watermark",
            "GRANT SELECT ON storage_deletion_debt",
            "GRANT SELECT, UPDATE ON storage_footprint_trust",
        )
        val asMonitor = db.connectAs(monitor)
        val asApi = db.connectAs(api)

        // The order is taken BEFORE measuring, in its own statement (contract §5.3).
        val started = asMonitor.sql("SELECT nextval('storage_debt_order_seq')").query(Long::class.java).single()
        asMonitor
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_seq, started_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total,
                     inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (?, clock_timestamp(), 'ops-monitor', 4096, 1, 1, 0, 1, 1, 0, 0)
                """.trimIndent(),
            ).param(started)
            .update() shouldBe 1
        permissionDenied { asMonitor.sql("SELECT count(*) FROM storage_filesystem_observation").query(Long::class.java).single() }
        permissionDenied { asMonitor.sql("DELETE FROM storage_filesystem_observation").update() }

        asApi.sql("SELECT count(*) FROM storage_filesystem_observation").query(Long::class.java).single() shouldBe 1
        permissionDenied {
            asApi
                .sql(
                    """
                    INSERT INTO storage_filesystem_observation
                        (started_seq, started_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total,
                         inodes_used, trash_bytes, minio_sys_bytes)
                    VALUES (1, clock_timestamp(), 'app', 4096, 1, 1, 0, 1, 1, 0, 0)
                    """.trimIndent(),
                ).update()
        }
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
    }

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
}
