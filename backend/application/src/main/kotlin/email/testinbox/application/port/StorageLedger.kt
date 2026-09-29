package email.testinbox.application.port

import java.util.UUID

/**
 * The ADR-035 §10 storage accounting ledger.
 *
 * Committed usage of a workspace or an inbox is `base + Σ delta`. Database
 * triggers append the deltas: that is the ADR-024 carve-out ADR-035 accepted.
 * This port is the only application-side writer of the base figures. Its two
 * operations are folding deltas into the bases (compaction) and repairing a
 * base proven wrong against the source rows (reconciliation).
 *
 * The source rows are authoritative: `message.raw_size_bytes` plus
 * `attachment.size_bytes`, the ADR-027 §5 derivation, in which an attachment
 * counts twice because both objects physically exist.
 *
 * Nothing here admits, refuses or enforces anything (TI-STORAGE-001).
 */
interface StorageLedger {
    /**
     * Folds up to [batch] committed deltas into the base figures, in one
     * transaction under the ledger's advisory lock.
     *
     * Returns without doing anything when another process holds that lock:
     * several API nodes run this schedule, and only one may fold at a time.
     */
    fun compact(batch: Int): LedgerCompaction

    /** Unfolded delta rows and the committed total across all workspaces, read in one snapshot. */
    fun state(): LedgerState

    /**
     * Where the ledger disagrees with the source rows. Read in one snapshot,
     * without any lock that would block a writer, so a clean result is cheap.
     */
    fun findDrift(): List<AccountingDrift>

    /**
     * Corrects every drifted base as `derived − Σ delta`, read from one
     * snapshot, under the ledger's advisory lock so that it cannot interleave
     * with a compaction. Returns what it corrected, which may be less than
     * [findDrift] found, since a concurrent compaction is not drift.
     */
    fun repairDrift(): List<AccountingDrift>
}

/** The outcome of one compaction pass. */
data class LedgerCompaction(
    /** False when another process held the ledger lock and this pass did nothing. */
    val lockAcquired: Boolean,
    val foldedRows: Int,
)

data class LedgerState(
    val unfoldedRows: Long,
    /** Σ base + Σ delta over all workspaces: the committed bytes the ledger accounts for. */
    val committedBytes: Long,
)

/** The scope an accounting figure belongs to. */
enum class AccountingScope { WORKSPACE, INBOX }

/**
 * One figure that disagrees with the source rows. [id] is the workspace or
 * inbox id. It goes into the operator log, never into a metric label.
 */
data class AccountingDrift(
    val scope: AccountingScope,
    val id: UUID,
    val derivedBytes: Long,
    val accountedBytes: Long,
) {
    val direction: DriftDirection
        get() = if (accountedBytes < derivedBytes) DriftDirection.UNDER else DriftDirection.OVER
}

/**
 * Which way the ledger was wrong. `UNDER` is the dangerous direction for
 * ADR-035, because a later admission would believe there was room.
 */
enum class DriftDirection { UNDER, OVER }
