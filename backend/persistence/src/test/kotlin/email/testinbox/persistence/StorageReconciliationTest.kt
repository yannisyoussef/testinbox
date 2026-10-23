package email.testinbox.persistence

import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.DriftDirection
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.usecase.ReconcileStorageAccounting
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §10 reconciliation: drift in either direction is found against the
 * source rows, repaired from one snapshot, and always metered.
 *
 * Drift is injected by writing a base directly. That is the defect
 * reconciliation exists to catch: in correct operation only the compactor
 * writes bases, so the ledger cannot drift.
 */
class StorageReconciliationTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var db: LedgerTestDatabase

    private class RecordingMetrics : StorageAccountingMetrics {
        val drift = mutableListOf<DriftDirection>()
        val outcomes = mutableListOf<ReconciliationOutcome>()

        override fun driftRepaired(direction: DriftDirection) {
            drift += direction
        }

        override fun reconciliationCompleted(outcome: ReconciliationOutcome) {
            outcomes += outcome
        }
    }

    @BeforeEach
    fun database() {
        db = LedgerTestDatabase.create(postgres, admin)
    }

    @AfterEach
    fun invariantAlwaysHolds() = db.assertInvariant("after the test body")

    private fun corruptWorkspace(
        workspace: UUID,
        by: Long,
    ) {
        db.jdbc
            .sql(
                """
                INSERT INTO workspace_storage_account (workspace_id, base_bytes) VALUES (?, ?)
                ON CONFLICT (workspace_id) DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes
                """.trimIndent(),
            ).params(workspace, by)
            .update()
    }

    @Test
    fun `a clean ledger reconciles CLEAN and changes nothing`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 100, attachments = listOf(10))
        db.ledger.compact(batch = 100)
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(db.ledger, metrics).reconcile() shouldBe ReconciliationOutcome.CLEAN

        metrics.outcomes shouldBe listOf(ReconciliationOutcome.CLEAN)
        metrics.drift shouldBe emptyList()
    }

    @Test
    fun `drift in both directions is repaired and metered by direction`() {
        val over = db.workspace()
        val under = db.workspace()
        val overInbox = db.inbox(over)
        val underInbox = db.inbox(under)
        db.message(over, overInbox, rawBytes = 100)
        db.message(under, underInbox, rawBytes = 200)
        db.ledger.compact(batch = 100)
        corruptWorkspace(over, +5_000) // the ledger claims more than exists
        corruptWorkspace(under, -150) // the ledger claims less: the dangerous direction
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(db.ledger, metrics).reconcile() shouldBe ReconciliationOutcome.REPAIRED

        metrics.drift shouldContainExactlyInAnyOrder listOf(DriftDirection.OVER, DriftDirection.UNDER)
        db.accountedWorkspace(over) shouldBe 100
        db.accountedWorkspace(under) shouldBe 200
        // Idempotent: a second run finds nothing.
        ReconcileStorageAccounting(db.ledger, metrics).reconcile() shouldBe ReconciliationOutcome.CLEAN
    }

    @Test
    fun `inbox drift is found and repaired too`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 60)
        db.ledger.compact(batch = 100)
        db.jdbc
            .sql("UPDATE inbox_storage SET base_bytes = base_bytes - 60 WHERE inbox_id = ?")
            .param(inbox)
            .update()

        val drift = db.ledger.findDrift()
        drift.single().scope shouldBe AccountingScope.INBOX
        drift.single().direction shouldBe DriftDirection.UNDER

        db.ledger
            .repairDrift()
            .single()
            .id shouldBe inbox
        db.accountedInbox(inbox) shouldBe 60
    }

    @Test
    fun `repair is exact with unfolded deltas outstanding`() {
        // base := derived − Σdelta. A repair that wrote `derived` would
        // double-count every delta still waiting to be folded.
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        db.ledger.compact(batch = 100)
        db.message(ws, inbox, rawBytes = 30) // unfolded
        corruptWorkspace(ws, +999)

        db.ledger.repairDrift()

        db.accountedWorkspace(ws) shouldBe 40
        db.ledger.compact(batch = 100)
        db.accountedWorkspace(ws) shouldBe 40
    }

    @Test
    fun `a clean detection takes no lock that a writer or the compactor could wait on`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 10)
        db.ledger.compact(batch = 100)
        // Everything a detection could touch is held by open transactions: the
        // ledger lock, a base row being updated, a writer mid-insert.
        val holder = db.openTransaction()
        holder.createStatement().use {
            it.execute("SELECT pg_advisory_xact_lock(35, 2)")
            it.execute("UPDATE workspace_storage_account SET reconciled_at = now() WHERE workspace_id = '$ws'")
            it.execute("LOCK TABLE message IN ROW EXCLUSIVE MODE")
        }
        try {
            val detection = Executors.newSingleThreadExecutor()
            try {
                // Returns promptly: MVCC reads only.
                detection.submit<Int> { db.ledger.findDrift().size }.get(10, TimeUnit.SECONDS) shouldBe 0
            } finally {
                detection.shutdownNow()
            }
        } finally {
            holder.rollback()
            holder.close()
        }
    }

    @Test
    fun `a repair waits for a running compaction and never interleaves with it`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 25)
        corruptWorkspace(ws, +1)
        val compaction = db.openTransaction()
        compaction.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(35, 2)") }

        val repairer = Executors.newSingleThreadExecutor()
        try {
            val repair = repairer.submit<Int> { db.ledger.repairDrift().size }
            db.awaitBlockedSessions()
            compaction.commit()
            compaction.close()
            repair.get(30, TimeUnit.SECONDS) shouldBe 1
        } finally {
            repairer.shutdownNow()
        }
        db.accountedWorkspace(ws) shouldBe 25
    }

    @Test
    fun `a write that commits during a repair is not lost`() {
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        db.message(ws, inbox, rawBytes = 5)
        db.ledger.compact(batch = 100)
        corruptWorkspace(ws, -5)
        // Uncommitted when the repair's snapshot is taken.
        val ingest = db.openTransaction()
        ingest
            .prepareStatement(
                """
                INSERT INTO message (id, workspace_id, inbox_id, received_at, provider, envelope_to, raw_object_key,
                                     raw_size_bytes, content_fingerprint, parse_status)
                VALUES (?, ?, ?, now(), 'smtp', 'x', 'k', 70, 'fp', 'OK')
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, ws)
                it.setObject(3, inbox)
                it.executeUpdate()
            }

        db.ledger.repairDrift()
        ingest.commit()
        ingest.close()

        // The late write's delta sits on top of the repaired base.
        db.accountedWorkspace(ws) shouldBe 75
    }
}
