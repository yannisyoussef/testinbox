package email.testinbox.persistence

import email.testinbox.application.usecase.CompactStorageLedger
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §10 compaction: each delta is folded into the bases exactly once,
 * atomically, and only by the process that holds the ledger lock.
 *
 * The concurrency cases are deterministic. Transactions are held open on real
 * connections, and blocking is proven with `pg_blocking_pids` rather than
 * inferred from a sleep.
 */
class StorageLedgerCompactionTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @AfterEach
    fun invariantAlwaysHolds() = db.assertInvariant("after the test body")

    private fun base(workspace: UUID): Long? =
        db.jdbc
            .sql("SELECT base_bytes FROM workspace_storage_account WHERE workspace_id = ?")
            .param(workspace)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    private fun inboxBase(inbox: UUID): Long? =
        db.jdbc
            .sql("SELECT base_bytes FROM inbox_storage WHERE inbox_id = ?")
            .param(inbox)
            .query(Long::class.java)
            .optional()
            .orElse(null)

    @Test
    fun `compaction folds every delta into the bases exactly once`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(10) { db.message(ws, inbox, rawBytes = 100, attachments = listOf(5)) }
        val unfolded = db.deltaRows()

        val first = db.ledger.compact(batch = 1_000)
        first.lockAcquired shouldBe true
        first.foldedRows shouldBe unfolded.toInt()
        db.deltaRows() shouldBe 0
        base(ws) shouldBe 1_050
        inboxBase(inbox) shouldBe 1_050

        // Nothing left: a second pass is a no-op, not a second fold.
        db.ledger.compact(batch = 1_000).foldedRows shouldBe 0
        base(ws) shouldBe 1_050
    }

    @Test
    fun `a workspace and an inbox created after the backfill get their base rows by upsert`() {
        // V6 backfilled only what existed. These have no base rows, and a plain
        // UPDATE would silently fold their bytes into nothing.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.jdbc
            .sql("SELECT count(*) FROM workspace_storage_account WHERE workspace_id = ?")
            .param(ws)
            .query(Int::class.java)
            .single() shouldBe 0
        db.message(ws, inbox, rawBytes = 42)

        db.ledger.compact(batch = 100)

        base(ws) shouldBe 42
        inboxBase(inbox) shouldBe 42
    }

    @Test
    fun `the deltas of a deleted inbox keep their workspace share and drop the inbox share`() {
        val ws = db.workspace()
        val doomed = db.inbox(ws)
        val kept = db.inbox(ws)
        db.message(ws, doomed, rawBytes = 500, attachments = listOf(50))
        db.message(ws, kept, rawBytes = 9)
        db.hardDeleteInbox(doomed)

        db.ledger.compact(batch = 100).lockAcquired shouldBe true

        base(ws) shouldBe 9
        inboxBase(doomed) shouldBe null
        inboxBase(kept) shouldBe 9
        db.deltaRows() shouldBe 0
    }

    @Test
    fun `a delta naming a workspace that does not exist cannot wedge compaction`() {
        // Only corrupt data produces one, since attachment.workspace_id has no
        // FK. It sits at the head of every batch, so failing on it would stop
        // compaction for good.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.jdbc
            .sql("INSERT INTO storage_delta (workspace_id, inbox_id, bytes) VALUES (gen_random_uuid(), NULL, 77)")
            .update()
        db.message(ws, inbox, rawBytes = 12)

        db.ledger.compact(batch = 100).foldedRows shouldBe 2

        base(ws) shouldBe 12
        db.deltaRows() shouldBe 0
    }

    @Test
    fun `a batch smaller than the backlog is folded over several passes by the use case`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        repeat(25) { db.message(ws, inbox, rawBytes = 4) }

        CompactStorageLedger(db.ledger, batch = 7, maxPasses = 10).compact() shouldBe 25

        db.deltaRows() shouldBe 0
        base(ws) shouldBe 100
    }

    @Test
    fun `a concurrent compactor finds the ledger lock taken and touches nothing`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 3)
        val other = db.openTransaction()
        other.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 2)") }

        val attempt = db.ledger.compact(batch = 100)

        attempt.lockAcquired shouldBe false
        attempt.foldedRows shouldBe 0
        db.deltaRows() shouldBe 1
        other.rollback()
        other.close()
        db.ledger.compact(batch = 100).foldedRows shouldBe 1
    }

    @Test
    fun `an uncommitted write's delta is invisible to compaction and folded by the next pass`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        val ingest = db.openTransaction()
        ingest
            .prepareStatement(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                                     raw_size_bytes, content_fingerprint, parse_status)
                VALUES (?, ?, ?, now(), 'smtp', 'x', 'k', 90, 'fp', 'OK')
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, ws)
                it.setObject(3, inbox)
                it.executeUpdate()
            }

        db.ledger.compact(batch = 100).foldedRows shouldBe 1 // only the committed delta
        base(ws) shouldBe 10

        ingest.commit()
        ingest.close()
        db.ledger.compact(batch = 100).foldedRows shouldBe 1
        base(ws) shouldBe 100
    }

    @Test
    fun `a gap in the delta id sequence loses nothing`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1)
        // A rolled-back write consumes delta ids that will never exist.
        val rolledBack = db.openTransaction()
        rolledBack
            .prepareStatement(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                                     raw_size_bytes, content_fingerprint, parse_status)
                SELECT gen_random_uuid(), ?, ?, now(), 'smtp', 'x', 'k', 1000, 'fp', 'OK' FROM generate_series(1, 3)
                """.trimIndent(),
            ).use {
                it.setObject(1, ws)
                it.setObject(2, inbox)
                it.executeUpdate()
            }
        rolledBack.rollback()
        rolledBack.close()
        db.message(ws, inbox, rawBytes = 2)

        db.ledger.compact(batch = 100).foldedRows shouldBe 2
        base(ws) shouldBe 3
    }

    @Test
    fun `an inbox deleted while compaction folds it rolls the pass back with every delta intact`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws) // created after the backfill: no inbox_storage row yet
        db.message(ws, inbox, rawBytes = 77)
        val unfolded = db.deltaRows()

        // Retention holds the inbox mid-delete, without committing.
        val retention = db.openTransaction()
        retention.prepareStatement("DELETE FROM inbox WHERE id = ?").use {
            it.setObject(1, inbox)
            it.executeUpdate()
        }

        val compactor = Executors.newSingleThreadExecutor()
        try {
            // The compactor still sees the inbox and inserts its base row. The
            // FK check then waits on retention's lock.
            val pass = compactor.submit<Any> { db.ledger.compact(batch = 100) }
            db.awaitBlockedSessions()
            retention.commit()
            retention.close()
            // Once retention commits, the inbox is gone: the FK check fails and
            // the whole pass rolls back.
            val failure = shouldThrowAny { pass.get(30, TimeUnit.SECONDS) }
            // The failure the design predicts, not merely any failure.
            failure.stackTraceToString() shouldContain "inbox_storage_inbox_id_fkey"
        } finally {
            compactor.shutdownNow()
        }

        // Nothing was lost. The rolled-back pass left its deltas, and retention
        // added its own cascade deltas on top.
        (db.deltaRows() >= unfolded) shouldBe true
        db.ledger.compact(batch = 100).lockAcquired shouldBe true
        base(ws) shouldBe 0
        db.deltaRows() shouldBe 0
    }
}
