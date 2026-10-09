package email.testinbox.persistence

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * The deletion-debt ledger and the Ops observations of the filesystem-
 * containment contract §5 on PostgreSQL (TI-STORAGE-006E, V8): ordering by
 * sequence, never by clock; server-issued observation order and times;
 * append-only observations and the compaction watermark; pending rows that no
 * compaction deletes.
 */
class StorageDeletionDebtTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @Test
    fun `with no observation every debt row counts and nothing is compacted`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 1_000, attachments = listOf(100))
        db.hardDeleteInbox(inbox)

        val debt = db.ledger.deletionDebt()
        debt.observation shouldBe null
        debt.unsupersededBytes shouldBe 1_100
        debt.unsupersededObjects shouldBe 2
        db.ledger.compactDeletionDebt() shouldBe 0
        db.debtRows() shouldHaveSize 2
    }

    @Test
    fun `an observation supersedes the debt ordered before it began and not the debt ordered after`() {
        val ws = db.workspace()
        val a = db.inbox(ws)
        val b = db.inbox(ws)
        db.message(ws, a, rawBytes = 1_000)
        db.message(ws, b, rawBytes = 5_000)
        db.hardDeleteInbox(a) // in the trash when the monitor measures

        val started = db.beginObservation()
        db.observe(trashBytes = 30_000, startedSeq = started)
        db.hardDeleteInbox(b) // moved after the measurement began: must be in the sum

        val debt = db.ledger.deletionDebt()
        checkNotNull(debt.observation).trashBytes shouldBe 30_000
        debt.observation?.startedSeq shouldBe started
        debt.unsupersededBytes shouldBe 5_000
        debt.unsupersededObjects shouldBe 1

        // Compaction removes only the superseded row; the later one stays for the next observation.
        db.ledger.compactDeletionDebt() shouldBe 1
        db.debtRows().map { it.first } shouldBe listOf(5_000L)
        db.ledger.deletionDebt().unsupersededBytes shouldBe 5_000
        db.ledger.deletionDebt().compactedThroughSeq shouldBe started
    }

    @Test
    fun `ordering is the sequence, not the clock - a debt row stamped in the past but ordered after the start counts`() {
        // A failover or an NTP step can move a clock backwards; the sequence never does.
        val started = db.beginObservation()
        db.observe(trashBytes = 1, startedSeq = started)
        db.jdbc
            .sql("INSERT INTO storage_deletion_debt (bytes, objects, incurred_at) VALUES (777, 1, now() - interval '1 day')")
            .update()

        db.ledger.deletionDebt().unsupersededBytes shouldBe 777
        db.ledger.compactDeletionDebt() shouldBe 0
        db.debtRows().map { it.first } shouldBe listOf(777L)
    }

    @Test
    fun `the newest observation is the walk begun last, not the row written last`() {
        val early = db.beginObservation()
        val late = db.beginObservation()
        db.observe(trashBytes = 2, startedSeq = late)
        db.observe(trashBytes = 3, startedSeq = early) // a late-arriving measurement that began earlier

        checkNotNull(db.ledger.deletionDebt().observation).trashBytes shouldBe 2
    }

    @Test
    fun `an order no walk began is refused - neither invented nor replayed`() {
        val issued = db.beginObservation()
        runCatching { db.observe(trashBytes = 1, startedSeq = issued + 1_000) }.isFailure shouldBe true
        db.observe(trashBytes = 1, startedSeq = issued)
        runCatching { db.observe(trashBytes = 1, startedSeq = issued) }.isFailure shouldBe true // one row per walk
        db.jdbc
            .sql("SELECT count(*) FROM storage_filesystem_observation")
            .query(Long::class.java)
            .single() shouldBe 1
    }

    @Test
    fun `the start, the end and the writer are stamped by the server, whatever the insert supplies`() {
        val started = db.beginObservation()
        val walkStart =
            db.jdbc
                .sql("SELECT started_at FROM storage_observation_walk WHERE started_seq = ?")
                .param(started)
                .query(java.time.OffsetDateTime::class.java)
                .single()
                .toInstant()
        db.jdbc
            .sql(
                """
                INSERT INTO storage_filesystem_observation
                    (started_seq, started_at, observed_at, written_by, source, block_size_bytes, capacity_bytes, used_bytes,
                     avail_bytes, inodes_total, inodes_used, trash_bytes, minio_sys_bytes)
                VALUES (?, now() + interval '1 day', now() + interval '1 day', 'ops_monitor', 'x', 4096, 0, 0, 0, 0, 0, 0, 0)
                """.trimIndent(),
            ).param(started)
            .update()
        val sessionUser =
            db.jdbc
                .sql("SELECT session_user::text")
                .query(String::class.java)
                .single()
        val row = checkNotNull(db.ledger.deletionDebt().observation)
        row.startedAt shouldBe walkStart
        row.writtenBy shouldBe sessionUser
        (row.observedAt >= walkStart && row.observedAt <= db.dbNow()) shouldBe true
    }

    @Test
    fun `observations are append-only - no update, no truncate, and neither the newest nor one at the watermark is deleted`() {
        db.observe(trashBytes = 1)
        db.jdbc.sql("INSERT INTO storage_deletion_debt (bytes, objects) VALUES (5, 1)").update()
        db.observe(trashBytes = 2)
        db.ledger.compactDeletionDebt() shouldBe 1 // ordered before the second observation began: superseded
        val watermark = db.ledger.deletionDebt().compactedThroughSeq

        listOf(
            "UPDATE storage_filesystem_observation SET trash_bytes = 0",
            "TRUNCATE storage_filesystem_observation",
            "DELETE FROM storage_filesystem_observation WHERE started_seq >= $watermark",
            "DELETE FROM storage_filesystem_observation",
        ).forEach { statement ->
            withClue(statement) { runCatching { db.jdbc.sql(statement).update() }.isFailure shouldBe true }
        }
        // Pruning below the watermark is what the monitor's retention does.
        db.jdbc
            .sql("DELETE FROM storage_filesystem_observation WHERE started_seq < ?")
            .param(watermark)
            .update() shouldBe 1
        checkNotNull(db.ledger.deletionDebt().observation).trashBytes shouldBe 2
    }

    @Test
    fun `a pending row counts under every observation, survives every compaction, and is resolved once`() {
        db.jdbc
            .sql("SELECT storage_record_pending_debt('ws/in/m/raw.eml', 4_096, 1, 'orphan-sweep')")
            .query()
            .listOfRows()
        // A retry before the S3 delete never adds a second row.
        db.jdbc
            .sql("SELECT storage_record_pending_debt('ws/in/m/raw.eml', 4_096, 1, 'orphan-sweep')")
            .query()
            .listOfRows()
        db.observe(trashBytes = 0)
        db.observe(trashBytes = 0)
        db.ledger.compactDeletionDebt() shouldBe 0
        db.ledger.deletionDebt().let {
            it.unsupersededBytes shouldBe 4_096
            it.pendingBytes shouldBe 4_096
        }
        // A pending row cannot be deleted by any compactor's predicate.
        db.jdbc.sql("DELETE FROM storage_deletion_debt WHERE seq < 9223372036854775807 AND incurred_at <> 'infinity'").update() shouldBe 0

        // Proven absent: resolved, now ordered after both observations, so it still counts.
        db.jdbc
            .sql("SELECT storage_resolve_pending_debt('ws/in/m/raw.eml')")
            .query(Int::class.java)
            .single() shouldBe 1
        db.jdbc
            .sql("SELECT storage_resolve_pending_debt('ws/in/m/raw.eml')")
            .query(Int::class.java)
            .single() shouldBe 0
        db.ledger.deletionDebt().let {
            it.unsupersededBytes shouldBe 4_096
            it.pendingBytes shouldBe 0
        }
        // Superseded only by an observation that began after the resolution.
        db.observe(trashBytes = 4_096)
        db.ledger.compactDeletionDebt() shouldBe 1
        db.ledger.deletionDebt().unsupersededBytes shouldBe 0
    }

    @Test
    fun `a pending row must name its key`() {
        runCatching {
            db.jdbc.sql("INSERT INTO storage_deletion_debt (bytes, objects, incurred_at) VALUES (1, 1, 'infinity')").update()
        }.isFailure shouldBe true
    }
}
