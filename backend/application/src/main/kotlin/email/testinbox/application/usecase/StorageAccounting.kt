package email.testinbox.application.usecase

import email.testinbox.application.port.AccountingDrift
import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.CompactionOutcome
import email.testinbox.application.port.DeletionDebtState
import email.testinbox.application.port.FootprintKind
import email.testinbox.application.port.ReconciliationOutcome
import email.testinbox.application.port.StorageAccountingMetrics
import email.testinbox.application.port.StorageLedger
import email.testinbox.domain.storage.FootprintModel
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

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
    /**
     * The footprint model the observed figures are bounded with (filesystem-
     * containment contract §3, TI-STORAGE-006E). The reference model until a
     * deployment declares its combination's own; observational in every mode.
     */
    private val footprint: FootprintModel = FootprintModel.REFERENCE,
    private val clock: Clock = Clock.systemUTC(),
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
            .onSuccess {
                metrics.ledgerObserved(it.unfoldedRows, it.committedBytes)
                // Bounds, never measurements: what the committed and reserved
                // objects CAN cost on the filesystem (contract §3.3).
                metrics.footprintObserved(
                    FootprintKind.COMMITTED,
                    footprint.bound(maxOf(0L, it.committedBytes), maxOf(0L, it.committedObjects)),
                )
                metrics.footprintObserved(
                    FootprintKind.RESERVED,
                    footprint.bound(maxOf(0L, it.reservedBytes), maxOf(0L, it.reservedObjects)),
                )
            }.onFailure { log.warn("storage ledger state could not be read", it) }
        observeDeletionDebt()
        return folded
    }

    /**
     * Contract §5: debt rows an observation has superseded are folded away,
     * and `D_est = trash_bytes(newest observation) + F(debt since it)` is
     * metered, together with the observation's age. Nothing here releases
     * debt on a timer: only an Ops observation ever lowers the estimate, and
     * with no observation the estimate is every debt row there is (§5.5).
     */
    private fun observeDeletionDebt() {
        runCatching { ledger.compactDeletionDebt() }
            .onFailure { log.warn("storage deletion debt could not be compacted; rows are intact", it) }
        runCatching {
            val debt = ledger.deletionDebt()
            // Both figures computed INSIDE the guard: an observation carrying an
            // absurd value (trash_bytes near Long.MAX) must not throw out of the
            // compaction tick, and must not leave the gauges at a stale "fresh".
            Triple(
                estimate(debt),
                debt.observation?.let { Duration.between(it.observedAt, clock.instant()).seconds } ?: NEVER_OBSERVED,
                debt.countsTrusted,
            )
        }.onSuccess { (estimate, age, trusted) ->
            metrics.footprintObserved(FootprintKind.DELETION_DEBT, estimate)
            metrics.filesystemObservationAge(age)
            metrics.footprintCountsTrusted(trusted)
        }.onFailure {
            log.warn("storage deletion debt could not be read or bounded; reported as unbounded and never observed", it)
            metrics.footprintObserved(FootprintKind.DELETION_DEBT, UNBOUNDED)
            metrics.filesystemObservationAge(NEVER_OBSERVED)
            metrics.footprintCountsTrusted(false)
        }
    }

    /** `D_est` (contract §5.3): the observed trash plus the bound of everything deleted since the observation began. */
    private fun estimate(debt: DeletionDebtState): Long =
        Math.addExact(
            debt.observation?.trashBytes ?: 0L,
            footprint.bound(debt.unsupersededBytes, debt.unsupersededObjects),
        )

    companion object {
        /** The age reported while no filesystem observation has ever been recorded, or the newest one cannot be read. */
        const val NEVER_OBSERVED: Long = -1

        /** The debt estimate reported when it cannot be computed: the conservative reading, never a stale small one. */
        const val UNBOUNDED: Long = Long.MAX_VALUE
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
                val result =
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
                // Contract §4.5: a pass that found no WORKSPACE-scope drift is what
                // makes the object counts trusted again. Inbox-only drift (what
                // paced retention leaves) never enters the global potential, so it
                // must not starve trust. The check is repeated under the ledger
                // lock and marked compare-and-set; a pass that repaired workspace
                // drift has just revoked trust and leaves it to the next pass.
                if (detected.none { it.scope == AccountingScope.WORKSPACE } && !ledger.confirmTrust()) {
                    log.warn("storage footprint counts remain untrusted; the next pass retries")
                }
                result
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
