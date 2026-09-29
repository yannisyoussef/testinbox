package email.testinbox.application.usecase

import email.testinbox.application.port.AccountingDrift
import email.testinbox.application.port.CompactionOutcome
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.port.StorageLedger
import org.slf4j.LoggerFactory

/**
 * ADR-035 §10 compaction: folds the trigger-written deltas into the base
 * figures, so that the one-statement usage read a later admission will make
 * stays small.
 *
 * It runs on a schedule on every API node. The ledger's advisory lock makes
 * all but one of them no-ops. Each pass is its own transaction, and a pass
 * that fails rolls back with its deltas intact for the next one.
 */
class CompactStorageLedger(
    private val ledger: StorageLedger,
    private val metrics: StorageAccountingMetrics = StorageAccountingMetrics.NOOP,
    private val batch: Int = DEFAULT_BATCH,
    private val maxPasses: Int = DEFAULT_MAX_PASSES,
) {
    init {
        require(batch > 0) { "batch must be positive" }
        require(maxPasses > 0) { "maxPasses must be positive" }
    }

    /**
     * Returns the number of delta rows folded by this call. It never throws. A
     * failed pass is logged and metered, and the backlog gauge is refreshed
     * whatever happened, so a failing compactor shows as a growing backlog, not
     * a frozen one.
     */
    fun compact(): Int {
        var folded = 0
        var passes = 0
        val outcome =
            runCatching {
                do {
                    val pass = ledger.compact(batch)
                    if (!pass.lockAcquired) return@runCatching if (passes == 0) CompactionOutcome.CONTENDED else CompactionOutcome.OK
                    folded += pass.foldedRows
                    passes++
                    // A short pass means the visible backlog is drained. The pass bound
                    // keeps one tick's work finite while writers keep appending.
                } while (pass.foldedRows == batch && passes < maxPasses)
                CompactionOutcome.OK
            }.getOrElse {
                log.warn("storage ledger compaction failed; deltas are intact and the next pass retries", it)
                CompactionOutcome.FAILED
            }
        metrics.compactionCompleted(outcome)
        runCatching { ledger.state() }
            .onSuccess { metrics.ledgerObserved(it.unfoldedRows, it.committedBytes) }
            .onFailure { log.warn("storage ledger state could not be read", it) }
        return folded
    }

    companion object {
        const val DEFAULT_BATCH = 5_000
        const val DEFAULT_MAX_PASSES = 20
        private val log = LoggerFactory.getLogger(CompactStorageLedger::class.java)
    }
}

/**
 * ADR-035 §10 reconciliation: proves the ledger against the source rows, and
 * repairs it where they disagree.
 *
 * A difference is always a defect. Triggers write the rows and their deltas in
 * one transaction, and compaction moves bytes atomically, so a correct ledger
 * never drifts. A repair is therefore always metered and logged. It is never
 * silent, and never merely reported and then left wrong.
 */
class ReconcileStorageAccounting(
    private val ledger: StorageLedger,
    private val metrics: StorageAccountingMetrics = StorageAccountingMetrics.NOOP,
) {
    fun reconcile(): ReconciliationOutcome {
        val outcome =
            runCatching {
                // Detection first, lock-free. A clean ledger costs one read and
                // takes no lock a writer could wait on.
                val detected = ledger.findDrift()
                if (detected.isEmpty()) {
                    ReconciliationOutcome.CLEAN
                } else {
                    // Logged before the repair, so a repair that then fails
                    // still leaves the detected drift on record.
                    log.warn("storage_accounting_drift_detected count={}; repairing under the ledger lock", detected.size)
                    val repaired = ledger.repairDrift()
                    repaired.forEach(::report)
                    // Another replica's reconciliation may have repaired it
                    // while this one waited for the ledger lock. Only a repair
                    // this node made counts.
                    if (repaired.isEmpty()) ReconciliationOutcome.CLEAN else ReconciliationOutcome.REPAIRED
                }
            }.getOrElse {
                log.warn("storage accounting reconciliation failed", it)
                ReconciliationOutcome.FAILED
            }
        metrics.reconciliationCompleted(outcome)
        return outcome
    }

    private fun report(drift: AccountingDrift) {
        metrics.driftRepaired(drift.direction)
        // Operator log only. The id never becomes a metric label (ADR-027 §14).
        log.warn(
            "storage_accounting_drift_repaired scope={} id={} direction={} derivedBytes={} accountedBytes={}",
            drift.scope,
            drift.id,
            drift.direction,
            drift.derivedBytes,
            drift.accountedBytes,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(ReconcileStorageAccounting::class.java)
    }
}
