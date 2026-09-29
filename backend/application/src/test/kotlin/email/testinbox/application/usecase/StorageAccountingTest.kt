package email.testinbox.application.usecase

import email.testinbox.application.port.AccountingDrift
import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.CompactionOutcome
import email.testinbox.application.port.DriftDirection
import email.testinbox.application.port.LedgerCompaction
import email.testinbox.application.port.LedgerState
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.port.StorageLedger
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The use-case logic around the ledger (ADR-035 §10): pass bounds, lock
 * contention, metrics, and failure handling. The database behaviour itself is
 * proven against PostgreSQL in the persistence suite.
 */
class StorageAccountingTest {
    private class FakeLedger(
        private val passes: MutableList<LedgerCompaction> = mutableListOf(),
        var drift: List<AccountingDrift> = emptyList(),
        var repaired: List<AccountingDrift> = drift,
        var failure: RuntimeException? = null,
        var compactFailure: RuntimeException? = null,
        var repairFailure: RuntimeException? = null,
    ) : StorageLedger {
        val batches = mutableListOf<Int>()
        var repairs = 0

        override fun compact(batch: Int): LedgerCompaction {
            batches += batch
            compactFailure?.let { throw it }
            return if (passes.isEmpty()) LedgerCompaction(true, 0) else passes.removeAt(0)
        }

        override fun state() = LedgerState(unfoldedRows = 3, committedBytes = 1_234)

        override fun findDrift(): List<AccountingDrift> {
            failure?.let { throw it }
            return drift
        }

        override fun repairDrift(): List<AccountingDrift> {
            repairs++
            repairFailure?.let { throw it }
            return repaired
        }
    }

    private class RecordingMetrics : StorageAccountingMetrics {
        var observed: Pair<Long, Long>? = null
        val drift = mutableListOf<DriftDirection>()
        val outcomes = mutableListOf<ReconciliationOutcome>()
        val compactions = mutableListOf<CompactionOutcome>()

        override fun compactionCompleted(outcome: CompactionOutcome) {
            compactions += outcome
        }

        override fun ledgerObserved(
            unfoldedRows: Long,
            committedBytes: Long,
        ) {
            observed = unfoldedRows to committedBytes
        }

        override fun driftRepaired(direction: DriftDirection) {
            drift += direction
        }

        override fun reconciliationCompleted(outcome: ReconciliationOutcome) {
            outcomes += outcome
        }
    }

    private fun drift(
        derived: Long,
        accounted: Long,
    ) = AccountingDrift(AccountingScope.WORKSPACE, UUID.randomUUID(), derived, accounted)

    @Test
    fun `compaction keeps passing while passes come back full, and stops at a short one`() {
        val ledger = FakeLedger(mutableListOf(LedgerCompaction(true, 10), LedgerCompaction(true, 10), LedgerCompaction(true, 4)))
        val metrics = RecordingMetrics()

        CompactStorageLedger(ledger, metrics, batch = 10, maxPasses = 50).compact() shouldBe 24

        ledger.batches shouldBe listOf(10, 10, 10)
        metrics.observed shouldBe (3L to 1_234L)
        metrics.compactions shouldBe listOf(CompactionOutcome.OK)
    }

    @Test
    fun `a failing compaction is absorbed, metered as FAILED, and still refreshes the backlog gauge`() {
        // Otherwise a compactor failing on every node leaves the backlog gauge
        // frozen at its last good value while the ledger grows.
        val ledger = FakeLedger(compactFailure = IllegalStateException("database gone"))
        val metrics = RecordingMetrics()

        CompactStorageLedger(ledger, metrics).compact() shouldBe 0

        metrics.compactions shouldBe listOf(CompactionOutcome.FAILED)
        metrics.observed shouldBe (3L to 1_234L)
    }

    @Test
    fun `one compaction tick is bounded however large the backlog`() {
        val ledger = FakeLedger(MutableList(1_000) { LedgerCompaction(true, 5) })

        CompactStorageLedger(ledger, batch = 5, maxPasses = 4).compact() shouldBe 20

        ledger.batches.size shouldBe 4
    }

    @Test
    fun `a compactor that finds the ledger lock taken does nothing more this tick`() {
        val ledger = FakeLedger(mutableListOf(LedgerCompaction(lockAcquired = false, foldedRows = 0), LedgerCompaction(true, 99)))

        val metrics = RecordingMetrics()

        CompactStorageLedger(ledger, metrics).compact() shouldBe 0

        ledger.batches.size shouldBe 1
        metrics.compactions shouldBe listOf(CompactionOutcome.CONTENDED)
    }

    @Test
    fun `a clean reconciliation never repairs`() {
        val ledger = FakeLedger()
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(ledger, metrics).reconcile() shouldBe ReconciliationOutcome.CLEAN

        ledger.repairs shouldBe 0
        metrics.outcomes shouldBe listOf(ReconciliationOutcome.CLEAN)
    }

    @Test
    fun `drift is repaired and metered by direction`() {
        val found = listOf(drift(derived = 10, accounted = 5), drift(derived = 10, accounted = 50))
        val ledger = FakeLedger(drift = found)
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(ledger, metrics).reconcile() shouldBe ReconciliationOutcome.REPAIRED

        metrics.drift shouldBe listOf(DriftDirection.UNDER, DriftDirection.OVER)
        metrics.outcomes shouldBe listOf(ReconciliationOutcome.REPAIRED)
    }

    @Test
    fun `drift another replica repaired first is not reported as drift by this one`() {
        // Two replicas can both detect the same drift. The second waits for the
        // ledger lock, then finds nothing left to repair. Only a repair this
        // node made counts.
        val ledger = FakeLedger(drift = listOf(drift(1, 2)), repaired = emptyList())
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(ledger, metrics).reconcile() shouldBe ReconciliationOutcome.CLEAN

        metrics.drift shouldBe emptyList()
    }

    @Test
    fun `a failing reconciliation is absorbed and metered as FAILED`() {
        val ledger = FakeLedger(failure = IllegalStateException("database gone"))
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(ledger, metrics).reconcile() shouldBe ReconciliationOutcome.FAILED

        metrics.outcomes shouldBe listOf(ReconciliationOutcome.FAILED)
    }

    @Test
    fun `a repair that fails after detecting drift is metered as FAILED, not CLEAN`() {
        val ledger = FakeLedger(drift = listOf(drift(10, 5)), repairFailure = IllegalStateException("lock timeout"))
        val metrics = RecordingMetrics()

        ReconcileStorageAccounting(ledger, metrics).reconcile() shouldBe ReconciliationOutcome.FAILED

        ledger.repairs shouldBe 1
        metrics.outcomes shouldBe listOf(ReconciliationOutcome.FAILED)
        metrics.drift shouldBe emptyList()
    }
}
