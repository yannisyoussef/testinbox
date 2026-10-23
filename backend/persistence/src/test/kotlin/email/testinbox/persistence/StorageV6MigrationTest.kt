package email.testinbox.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §14: V6 backfills exactly, and it fails fast instead of queueing
 * behind a writer.
 *
 * Each case upgrades a database that V5 populated. An empty-database run would
 * pass a backfill that silently skipped every existing row.
 */
class StorageV6MigrationTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private fun atV5(): LedgerTestDatabase = LedgerTestDatabase.create(postgres, admin, target = "5")

    private fun upgrade(db: LedgerTestDatabase) = LedgerTestDatabase.flyway(db.dataSource).migrate()

    private fun baseOf(
        db: LedgerTestDatabase,
        workspace: UUID,
    ): Long? =
        db.jdbc
            .sql("SELECT base_bytes FROM workspace_storage_account WHERE workspace_id = ?")
            .param(workspace)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    // --- A. backfill ---------------------------------------------------------------

    @Test
    fun `an empty database upgrades to empty accounting`() {
        val db = atV5()
        upgrade(db).success shouldBe true
        db.jdbc
            .sql("SELECT (SELECT count(*) FROM workspace_storage_account) + (SELECT count(*) FROM storage_delta)")
            .query(Long::class.java)
            .single() shouldBe 0
    }

    @Test
    fun `existing rows are backfilled exactly, attachments twice, parse failures raw only`() {
        val db = atV5()
        val ws = db.workspace()
        val full = db.inbox(ws)
        val idle = db.inbox(ws)
        val quiet = db.workspace()
        db.message(ws, full, rawBytes = 1_000, attachments = listOf(100, 50))
        db.message(ws, full, rawBytes = 2_000)
        // A parse failure stores raw.eml only, so it has no attachment rows.
        db.jdbc
            .sql(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                                     raw_size_bytes, content_fingerprint, parse_status, parse_error)
                VALUES (?, ?, ?, now(), 'smtp', 'x', 'k', 333, 'fp', 'FAILED', 'broken')
                """.trimIndent(),
            ).params(UUID.randomUUID(), ws, full)
            .update()

        upgrade(db).success shouldBe true

        baseOf(db, ws) shouldBe 1_000 + 150 + 2_000 + 333
        baseOf(db, quiet) shouldBe 0
        db.accountedInbox(full) shouldBe 3_483
        db.accountedInbox(idle) shouldBe 0
        // The backfill ran under V6's locks: nothing was left for the triggers.
        db.deltaRows() shouldBe 0
        db.assertInvariant("after the V6 backfill")
    }

    @Test
    fun `a workspace already over the ADR-035 ceiling is backfilled as it is, and nothing is deleted`() {
        // No grandfathering and no eviction (ADR-035 I11): the migration
        // records what exists. Enforcement is a later slice.
        val db = atV5()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(3) { db.message(ws, inbox, rawBytes = 1_073_741_824) } // 3 GiB of metadata-declared raw bytes
        val messagesBefore =
            db.jdbc
                .sql("SELECT count(*) FROM message")
                .query(Long::class.java)
                .single()

        upgrade(db)

        baseOf(db, ws) shouldBe 3L * 1_073_741_824
        db.jdbc
            .sql("SELECT count(*) FROM message")
            .query(Long::class.java)
            .single() shouldBe messagesBefore
    }

    @Test
    fun `writes made after the upgrade are captured by the triggers, not lost between backfill and capture`() {
        val db = atV5()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        upgrade(db)

        db.message(ws, inbox, rawBytes = 20, attachments = listOf(5))

        db.accountedWorkspace(ws) shouldBe 35
        db.assertInvariant("after a post-upgrade write")
    }

    @Test
    fun `the future ADR-035 tables are created empty and nothing writes to them`() {
        val db = atV5()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        upgrade(db)
        db.message(ws, inbox, rawBytes = 10)
        db.hardDeleteInbox(inbox)

        for (table in listOf("storage_reservation", "storage_ambiguity", "storage_node", "storage_admission_latch")) {
            db.jdbc
                .sql("SELECT count(*) FROM $table")
                .query(Long::class.java)
                .single() shouldBe 0
        }
    }

    // --- §6. locking ---------------------------------------------------------------------

    private val v6Script: String =
        requireNotNull(javaClass.classLoader.getResource("db/migration/V6__storage_accounting_foundation.sql"))
            .readText()

    @Test
    fun `V6 takes its locks first, in retention's order, with a 30 s bound on the whole LOCK`() {
        val statements =
            v6Script
                .lines()
                .filterNot { it.trimStart().startsWith("--") || it.isBlank() }
                .take(4)
        statements shouldBe
            listOf(
                "SET LOCAL lock_timeout = '30s';",
                // lock_timeout bounds each table's wait; this bounds the whole LOCK.
                "SET LOCAL statement_timeout = '30s';",
                // inbox before message: a DELETE FROM inbox locks the inbox and
                // then cascades into message. The opposite order deadlocked in
                // the database review's reproduction.
                "LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;",
                "RESET statement_timeout;",
            )
    }

    /** V1–V5 as bundled, plus a V6 whose only difference is the lock timeout. */
    private fun migrationsWithTimeout(
        dir: Path,
        timeout: String,
    ): String {
        for (name in listOf(
            "V1__initial_schema.sql",
            "V2__recipient_scoped_provider_delivery.sql",
            "V3__rate_limits_and_quotas.sql",
            "V4__managed_api_keys.sql",
            "V5__idempotency_records.sql",
        )) {
            Files.writeString(dir.resolve(name), requireNotNull(javaClass.classLoader.getResource("db/migration/$name")).readText())
        }
        val needle = "SET LOCAL lock_timeout = '30s';"
        v6Script.split(needle).size shouldBe 2 // exactly one occurrence is substituted
        Files.writeString(
            dir.resolve("V6__storage_accounting_foundation.sql"),
            v6Script.replace(needle, "SET LOCAL lock_timeout = '$timeout';"),
        )
        return "filesystem:$dir"
    }

    @Test
    fun `an unavailable lock makes V6 fail cleanly instead of blocking ingress`(
        @TempDir dir: Path,
    ) {
        val db = atV5()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        // A writer mid-transaction on message, as an in-flight ingestion would be.
        val writer = db.openTransaction()
        writer.createStatement().use { it.execute("LOCK TABLE message IN ROW EXCLUSIVE MODE") }

        val started = System.nanoTime()
        val failure =
            shouldThrow<FlywayException> {
                LedgerTestDatabase.flyway(db.dataSource, locations = migrationsWithTimeout(dir, "1s")).migrate()
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        failure.stackTraceToString() shouldContain "lock timeout"
        (elapsedMs < 20_000) shouldBe true
        // Nothing half-applied: PostgreSQL DDL is transactional, and no success is recorded.
        db.jdbc
            .sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'storage_delta'")
            .query(Int::class.java)
            .single() shouldBe 0
        db.jdbc
            .sql("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success")
            .query(Int::class.java)
            .single() shouldBe 0

        writer.rollback()
        writer.close()
        // Once the writer is gone, the real migration applies.
        upgrade(db).success shouldBe true
        db.message(ws, inbox, rawBytes = 5)
        db.assertInvariant("after a retried V6")
    }

    @Test
    fun `a retention delete waiting on V6 completes after it, and its cascade is accounted`() {
        val db = atV5()
        val ws = db.workspace()
        val doomed = db.inbox(ws)
        val kept = db.inbox(ws)
        db.message(ws, doomed, rawBytes = 100, attachments = listOf(10))
        db.message(ws, kept, rawBytes = 7)

        // V6 in a transaction we control, holding its locks.
        val migration = db.openTransaction()
        migration.createStatement().use { it.execute(v6Script) }

        val retention = Executors.newSingleThreadExecutor()
        try {
            val deleted =
                retention.submit<Int> {
                    db.jdbc
                        .sql("DELETE FROM inbox WHERE id = ?")
                        .param(doomed)
                        .update()
                }
            db.awaitBlockedSessions()

            migration.commit()
            migration.close()

            deleted.get(30, TimeUnit.SECONDS) shouldBe 1
        } finally {
            retention.shutdownNow()
        }
        db.accountedWorkspace(ws) shouldBe 7
        db.assertInvariant("after retention queued behind V6")
    }

    /**
     * Retention here has finished its delete and sits idle in its transaction,
     * so this test pins the lock timeout, not the lock order. The mid-cascade
     * tests below pin the order.
     */
    @Test
    fun `V6 queued behind an uncommitted retention delete fails on its timeout`(
        @TempDir dir: Path,
    ) {
        val db = atV5()
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100)
        // Retention mid-transaction: it holds the inbox (and, through the
        // cascade, message) and has not committed.
        val retention = db.openTransaction()
        retention.prepareStatement("DELETE FROM inbox WHERE id = ?").use {
            it.setObject(1, inbox)
            it.executeUpdate()
        }

        val failure =
            shouldThrow<FlywayException> {
                LedgerTestDatabase.flyway(db.dataSource, locations = migrationsWithTimeout(dir, "1s")).migrate()
            }
        failure.stackTraceToString() shouldContain "lock timeout"

        retention.commit()
        retention.close()
        upgrade(db).success shouldBe true
        db.assertInvariant("after V6 retried behind retention")
    }

    /**
     * Runs [v6] against a retention delete frozen *inside* its cascade. The
     * delete holds `inbox` and `message` and has not yet reached `attachment`.
     * A gate trigger on `message` (in this throwaway database only) parks it
     * there on an advisory lock the test holds. This is the interleaving where
     * lock order decides between queueing and deadlock. Once V6 is queued, the
     * gate opens and both are left to finish. Returns every failure.
     */
    private fun v6AgainstRetentionMidCascade(
        db: LedgerTestDatabase,
        doomed: UUID,
        v6: String,
    ): List<Throwable> {
        db.jdbc
            .sql(
                """
                CREATE FUNCTION test_cascade_gate() RETURNS trigger LANGUAGE plpgsql AS
                ${'$'}${'$'} BEGIN PERFORM pg_advisory_xact_lock(99, 1); RETURN NULL; END ${'$'}${'$'};
                CREATE TRIGGER test_cascade_gate BEFORE DELETE ON message
                    FOR EACH STATEMENT EXECUTE FUNCTION test_cascade_gate();
                """.trimIndent(),
            ).update()
        val gate = db.openTransaction()
        gate.createStatement().use { it.execute("SELECT pg_advisory_lock(99, 1)") }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val retention =
                pool.submit<Int> {
                    db.jdbc
                        .sql("DELETE FROM inbox WHERE id = ?")
                        .param(doomed)
                        .update()
                }
            db.awaitBlockedSessions(atLeast = 1) // retention, parked mid-cascade
            val migration =
                pool.submit {
                    db.openTransaction().use { c ->
                        c.createStatement().use { it.execute(v6) }
                        c.commit()
                    }
                }
            db.awaitBlockedSessions(atLeast = 2) // and V6, queued behind it
            gate.createStatement().use { it.execute("SELECT pg_advisory_unlock(99, 1)") }
            gate.rollback()
            gate.close()

            return listOf(retention, migration).mapNotNull { future ->
                runCatching { future.get(60, TimeUnit.SECONDS) }.exceptionOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun seededForCascade(): Triple<LedgerTestDatabase, UUID, UUID> {
        val db = atV5()
        val ws = db.workspace()
        val doomed = db.inbox(ws)
        val kept = db.inbox(ws)
        db.message(ws, doomed, rawBytes = 100, attachments = listOf(10))
        db.message(ws, kept, rawBytes = 7, attachments = listOf(3))
        return Triple(db, ws, doomed)
    }

    @Test
    fun `V6 queues behind a retention delete caught mid-cascade, and neither deadlocks`() {
        val (db, ws, doomed) = seededForCascade()

        v6AgainstRetentionMidCascade(db, doomed, v6Script) shouldBe emptyList()

        db.accountedWorkspace(ws) shouldBe 7 + 3
        db.assertInvariant("after V6 queued behind a mid-cascade retention delete")
    }

    @Test
    fun `the same interleaving deadlocks with the lock order reversed, so the test above can fail`() {
        // The mutant V6 takes attachment before message. It holds attachment
        // while waiting for retention's message lock, and retention's cascade
        // then needs attachment: a cycle PostgreSQL has to break.
        val (db, _, doomed) = seededForCascade()
        val correct = "LOCK TABLE workspace, inbox, message, attachment IN SHARE ROW EXCLUSIVE MODE;"
        // Only the migration's own LOCK, the first occurrence. The recompute
        // function repeats the list without the trailing semicolon.
        val reversed = v6Script.replaceFirst(correct, "LOCK TABLE attachment, message, inbox, workspace IN SHARE ROW EXCLUSIVE MODE;")
        (reversed != v6Script) shouldBe true

        val failures = v6AgainstRetentionMidCascade(db, doomed, reversed)

        failures.map { it.stackTraceToString() }.any { "deadlock detected" in it } shouldBe true
    }
}
