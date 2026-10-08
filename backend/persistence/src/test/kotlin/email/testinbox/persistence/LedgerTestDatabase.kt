package email.testinbox.persistence

import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/**
 * An isolated, freshly created database for the ADR-035 accounting tests.
 *
 * It is isolated because the ledger is global state. Compaction folds every
 * workspace's deltas, and reconciliation reads every row. On the shared test
 * database, any other suite's fixture that cleared a table without firing the
 * triggers would look like drift, and a clean-reconciliation assertion there
 * would be proving nothing about this code.
 *
 * [assertInvariant] is written independently of `JdbcStorageLedger`'s drift
 * query. It re-derives usage with its own SQL, so a bug shared by the adapter
 * and its checker cannot cancel out.
 */
class LedgerTestDatabase private constructor(
    val dataSource: DataSource,
    val name: String,
) {
    val jdbc: JdbcClient = JdbcClient.create(dataSource)
    val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    val ledger = JdbcStorageLedger(jdbc, transactions)

    // --- fixtures -------------------------------------------------------------

    fun workspace(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO workspace (id, name, created_at) VALUES (?, 'w', now())").param(id).update()
        jdbc
            .sql("INSERT INTO project (id, workspace_id, name, created_at) VALUES (?, ?, 'p', now())")
            .params(id, id)
            .update()
        return id
    }

    fun inbox(workspace: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                VALUES (?, ?, ?, ?, 'GENERATED', 'ACTIVE', now(), now() + interval '1 hour')
                """.trimIndent(),
            ).params(id, workspace, workspace, "$id@ledger.test")
            .update()
        return id
    }

    /** Inserts one message and its attachments, each in its own statement, as `JdbcMessageRepository` does. */
    fun message(
        workspace: UUID,
        inbox: UUID,
        rawBytes: Long,
        attachments: List<Long> = emptyList(),
        providerMessageId: String? = null,
        connection: JdbcClient = jdbc,
    ): UUID? {
        val id = UUID.randomUUID()
        val inserted =
            connection
                .sql(
                    """
                    INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, provider_message_id,
                                         envelope_to, raw_object_key, raw_size_bytes, content_fingerprint, parse_status)
                    VALUES (:id, :ws, :inbox, now(), 'ses', :pmid, 'x@ledger.test', 'k', :raw, 'fp', 'OK')
                    ON CONFLICT (provider, provider_message_id, envelope_to)
                        WHERE provider_message_id IS NOT NULL DO NOTHING
                    """.trimIndent(),
                ).param("id", id)
                .param("ws", workspace)
                .param("inbox", inbox)
                .param("pmid", providerMessageId)
                .param("raw", rawBytes)
                .update()
        if (inserted == 0) return null
        for (size in attachments) {
            connection
                .sql(
                    """
                    INSERT INTO attachment (id, workspace_id, message_id, size_bytes, object_key)
                    VALUES (?, ?, ?, ?, 'k')
                    """.trimIndent(),
                ).params(UUID.randomUUID(), workspace, id, size)
                .update()
        }
        return id
    }

    fun hardDeleteInbox(inbox: UUID) {
        // Exactly what JdbcInboxRepository.hardDelete runs: one statement, cascading.
        jdbc.sql("DELETE FROM inbox WHERE id = ?").param(inbox).update()
    }

    fun deltaRows(): Long = jdbc.sql("SELECT count(*) FROM storage_delta").query(Long::class.java).single()

    // --- the independent invariant ----------------------------------------------

    /** ADR-027 §5: raw bytes plus every attachment (attachments count twice, physically). */
    fun derivedWorkspace(workspace: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(raw_size_bytes), 0) FROM message WHERE workspace_id = ?)
                     + (SELECT coalesce(sum(size_bytes), 0) FROM attachment WHERE workspace_id = ?)
                """.trimIndent(),
            ).params(workspace, workspace)
            .query(Long::class.java)
            .single()

    fun accountedWorkspace(workspace: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account WHERE workspace_id = ?)
                     + (SELECT coalesce(sum(bytes), 0) FROM storage_delta WHERE workspace_id = ?)
                """.trimIndent(),
            ).params(workspace, workspace)
            .query(Long::class.java)
            .single()

    fun derivedInbox(inbox: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(raw_size_bytes), 0) FROM message WHERE inbox_id = ?)
                     + (SELECT coalesce(sum(a.size_bytes), 0)
                          FROM attachment a JOIN message m ON m.id = a.message_id WHERE m.inbox_id = ?)
                """.trimIndent(),
            ).params(inbox, inbox)
            .query(Long::class.java)
            .single()

    fun accountedInbox(inbox: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(base_bytes), 0) FROM inbox_storage WHERE inbox_id = ?)
                     + (SELECT coalesce(sum(bytes), 0) FROM storage_delta WHERE inbox_id = ?)
                """.trimIndent(),
            ).params(inbox, inbox)
            .query(Long::class.java)
            .single()

    // --- objects and deletion debt (TI-STORAGE-006E) --------------------------------

    fun derivedWorkspaceObjects(workspace: UUID): Long =
        jdbc
            .sql(
                "SELECT (SELECT count(*) FROM message WHERE workspace_id = ?) + (SELECT count(*) FROM attachment WHERE workspace_id = ?)",
            ).params(workspace, workspace)
            .query(Long::class.java)
            .single()

    fun accountedWorkspaceObjects(workspace: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(base_objects), 0) FROM workspace_storage_account WHERE workspace_id = ?)
                     + (SELECT coalesce(sum(objects), 0) FROM storage_delta WHERE workspace_id = ?)
                """.trimIndent(),
            ).params(workspace, workspace)
            .query(Long::class.java)
            .single()

    fun accountedInboxObjects(inbox: UUID): Long =
        jdbc
            .sql(
                """
                SELECT (SELECT coalesce(sum(base_objects), 0) FROM inbox_storage WHERE inbox_id = ?)
                     + (SELECT coalesce(sum(objects), 0) FROM storage_delta WHERE inbox_id = ?)
                """.trimIndent(),
            ).params(inbox, inbox)
            .query(Long::class.java)
            .single()

    /** Every deletion-debt row: (bytes, objects, incurred_at), oldest first. */
    fun debtRows(): List<Triple<Long, Long, java.time.Instant>> =
        jdbc
            .sql("SELECT bytes, objects, incurred_at FROM storage_deletion_debt ORDER BY id")
            .query { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), checkNotNull(Timestamps.fromDb(rs, "incurred_at"))) }
            .list()

    fun dbNow(): java.time.Instant =
        jdbc
            .sql("SELECT clock_timestamp()")
            .query(java.time.OffsetDateTime::class.java)
            .single()
            .toInstant()

    /** What the Ops monitor writes: started_at read from the database clock first, then the measurement. */
    fun observe(
        trashBytes: Long,
        startedAt: java.time.Instant = dbNow(),
        usedBytes: Long = 0,
        availBytes: Long = 0,
        capacityBytes: Long = 0,
        minioSysBytes: Long = 0,
        source: String = "test-monitor",
    ) {
        jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_at, source, block_size_bytes, capacity_bytes, used_bytes, avail_bytes, inodes_total, inodes_used,
                     trash_bytes, minio_sys_bytes)
                VALUES (?, ?, 4096, ?, ?, ?, 0, 0, ?, ?)
                """.trimIndent(),
            ).params(Timestamps.toDb(startedAt), source, capacityBytes, usedBytes, availBytes, trashBytes, minioSysBytes)
            .update()
    }

    fun liveInboxes(): List<UUID> =
        jdbc
            .sql("SELECT id FROM inbox")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun workspaces(): List<UUID> =
        jdbc
            .sql("SELECT id FROM workspace")
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /**
     * Every workspace, and every live inbox not in [excludedInboxes], accounts
     * exactly for its rows.
     *
     * One aggregate query per scope rather than one per figure, so that a
     * property test can afford to call this after every step. The derivation
     * is still its own SQL, written independently of the adapter's drift query.
     */
    fun assertInvariant(
        context: String = "",
        excludedInboxes: Set<UUID> = emptySet(),
    ) {
        if (!hasObjectCounts()) return assertByteInvariant(context, excludedInboxes)
        val workspaceMismatches =
            jdbc
                .sql(
                    """
                    SELECT w.id,
                           coalesce((SELECT sum(raw_size_bytes) FROM message m WHERE m.workspace_id = w.id), 0)
                         + coalesce((SELECT sum(size_bytes) FROM attachment a WHERE a.workspace_id = w.id), 0) AS derived,
                           coalesce((SELECT base_bytes FROM workspace_storage_account s WHERE s.workspace_id = w.id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_delta d WHERE d.workspace_id = w.id), 0) AS accounted,
                           (SELECT count(*) FROM message m WHERE m.workspace_id = w.id)
                         + (SELECT count(*) FROM attachment a WHERE a.workspace_id = w.id) AS derived_objects,
                           coalesce((SELECT base_objects FROM workspace_storage_account s WHERE s.workspace_id = w.id), 0)
                         + coalesce((SELECT sum(objects) FROM storage_delta d WHERE d.workspace_id = w.id), 0) AS accounted_objects
                      FROM workspace w
                    """.trimIndent(),
                ).query { rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)) }
                .list()
                .filter { it[1] != it[2] || it[3] != it[4] }
        val inboxMismatches =
            jdbc
                .sql(
                    """
                    SELECT i.id,
                           coalesce((SELECT sum(raw_size_bytes) FROM message m WHERE m.inbox_id = i.id), 0)
                         + coalesce((SELECT sum(a.size_bytes) FROM attachment a JOIN message m ON m.id = a.message_id
                                      WHERE m.inbox_id = i.id), 0) AS derived,
                           coalesce((SELECT base_bytes FROM inbox_storage s WHERE s.inbox_id = i.id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_delta d WHERE d.inbox_id = i.id), 0) AS accounted,
                           (SELECT count(*) FROM message m WHERE m.inbox_id = i.id)
                         + (SELECT count(*) FROM attachment a JOIN message m ON m.id = a.message_id WHERE m.inbox_id = i.id) AS derived_objects,
                           coalesce((SELECT base_objects FROM inbox_storage s WHERE s.inbox_id = i.id), 0)
                         + coalesce((SELECT sum(objects) FROM storage_delta d WHERE d.inbox_id = i.id), 0) AS accounted_objects
                      FROM inbox i
                    """.trimIndent(),
                ).query { rs, _ -> listOf(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)) }
                .list()
                .filter { (it[1] != it[2] || it[3] != it[4]) && it[0] !in excludedInboxes }
        withClue("ADR-035 invariant $context: workspace (id, derived, accounted, derivedObjects, accountedObjects) mismatches") {
            workspaceMismatches shouldBe emptyList()
        }
        withClue("ADR-035 invariant $context: inbox (id, derived, accounted, derivedObjects, accountedObjects) mismatches") {
            inboxMismatches shouldBe emptyList()
        }
    }

    /** Whether V8 has run here: the V6 migration tests exercise the pre-V8 schema, where only bytes exist. */
    private fun hasObjectCounts(): Boolean =
        jdbc
            .sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'storage_delta' AND column_name = 'objects'")
            .query(Int::class.java)
            .single() == 1

    /** The V6-era invariant, bytes only, for a database that has not reached V8. */
    private fun assertByteInvariant(
        context: String,
        excludedInboxes: Set<UUID>,
    ) {
        val workspaceMismatches =
            jdbc
                .sql(
                    """
                    SELECT w.id,
                           coalesce((SELECT sum(raw_size_bytes) FROM message m WHERE m.workspace_id = w.id), 0)
                         + coalesce((SELECT sum(size_bytes) FROM attachment a WHERE a.workspace_id = w.id), 0) AS derived,
                           coalesce((SELECT base_bytes FROM workspace_storage_account s WHERE s.workspace_id = w.id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_delta d WHERE d.workspace_id = w.id), 0) AS accounted
                      FROM workspace w
                    """.trimIndent(),
                ).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getLong(3)) }
                .list()
                .filter { it.second != it.third }
        val inboxMismatches =
            jdbc
                .sql(
                    """
                    SELECT i.id,
                           coalesce((SELECT sum(raw_size_bytes) FROM message m WHERE m.inbox_id = i.id), 0)
                         + coalesce((SELECT sum(a.size_bytes) FROM attachment a JOIN message m ON m.id = a.message_id
                                      WHERE m.inbox_id = i.id), 0) AS derived,
                           coalesce((SELECT base_bytes FROM inbox_storage s WHERE s.inbox_id = i.id), 0)
                         + coalesce((SELECT sum(bytes) FROM storage_delta d WHERE d.inbox_id = i.id), 0) AS accounted
                      FROM inbox i
                    """.trimIndent(),
                ).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getLong(3)) }
                .list()
                .filter { it.second != it.third && it.first !in excludedInboxes }
        withClue("ADR-035 invariant $context: workspace (id, derived, accounted) mismatches") {
            workspaceMismatches shouldBe emptyList()
        }
        withClue("ADR-035 invariant $context: inbox (id, derived, accounted) mismatches") {
            inboxMismatches shouldBe emptyList()
        }
    }

    // --- concurrency ---------------------------------------------------------------

    /** A raw connection in an open transaction, for holding locks across steps of a test. */
    fun openTransaction(): Connection = dataSource.connection.also { it.autoCommit = false }

    /** Waits until at least one session of this database is blocked by another, instead of sleeping. */
    fun awaitBlockedSessions(
        atLeast: Int = 1,
        timeoutSeconds: Long = 30,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val blocked =
                jdbc
                    .sql(
                        "SELECT count(*) FROM pg_stat_activity " +
                            "WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0",
                    ).query(Int::class.java)
                    .single()
            if (blocked >= atLeast) return
            LockSupport.parkNanos(POLL_NANOS)
        }
        error("no session became blocked within ${timeoutSeconds}s")
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(clue) { block() }

    companion object {
        /** A short park between polls, so a wait is a check, not a busy spin. */
        private val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(5)

        /** A new database on the shared container, migrated to [target], or to the head when null. */
        fun create(
            postgres: PostgreSQLContainer<*>,
            admin: JdbcClient,
            target: String? = null,
        ): LedgerTestDatabase {
            val name = "ledger_${UUID.randomUUID().toString().replace("-", "")}"
            admin.sql("CREATE DATABASE $name").update()
            val dataSource =
                SimpleDriverDataSource(
                    org.postgresql.Driver(),
                    postgres.jdbcUrl.substringBeforeLast('/') + "/" + name,
                    postgres.username,
                    postgres.password,
                )
            flyway(dataSource, target).migrate()
            return LedgerTestDatabase(dataSource, name)
        }

        fun flyway(
            dataSource: DataSource,
            target: String? = null,
            locations: String = "classpath:db/migration",
        ): Flyway =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(locations)
                .let { if (target != null) it.target(target) else it }
                .load()
    }
}
