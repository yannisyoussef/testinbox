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

    /** Unfolded delta rows, the committed totals and the reserved totals across all workspaces, read in one snapshot. */
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

    /**
     * The deletion-debt inputs of the filesystem-containment contract §5.3
     * (TI-STORAGE-006E), read in one snapshot: the newest Ops observation's
     * trash bytes and the debt rows incurred since that observation began. With
     * no observation ever recorded, EVERY debt row counts and [DeletionDebtState.observation]
     * is null — the sound, growing estimate the contract's §5.5 calls for.
     */
    fun deletionDebt(): DeletionDebtState

    /**
     * Deletes the debt rows the newest observation has superseded (incurred
     * before it began): their bytes are inside its `trash_bytes` now. Returns
     * how many rows went. Nothing is deleted while no observation exists.
     */
    fun compactDeletionDebt(): Int
}

/** What the ledger knows about deleted-but-possibly-unpurged objects (contract §5). */
data class DeletionDebtState(
    /** Σ bytes of the debt rows not yet superseded by an observation. */
    val unsupersededBytes: Long,
    /** Σ objects of those rows. */
    val unsupersededObjects: Long,
    /** The newest observation, or null if Ops has never written one. */
    val observation: FilesystemObservation?,
)

/** One row of `storage_filesystem_observation`, as the Ops monitor wrote it (contract §6). Data, never an instruction. */
data class FilesystemObservation(
    val startedAt: java.time.Instant,
    val observedAt: java.time.Instant,
    val source: String,
    val blockSizeBytes: Long,
    val capacityBytes: Long,
    val usedBytes: Long,
    val availBytes: Long,
    val inodesTotal: Long,
    val inodesUsed: Long,
    val trashBytes: Long,
    val minioSysBytes: Long,
)

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
    /** Σ base_objects + Σ delta.objects over all workspaces: the committed objects (TI-STORAGE-006E). */
    val committedObjects: Long = 0,
    /** Σ bytes of every unreleased reservation, read in the same snapshot. */
    val reservedBytes: Long = 0,
    /** Σ cardinality(object_keys) of every unreleased reservation. */
    val reservedObjects: Long = 0,
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
    /** `count(message) + count(attachment)` of the scope (TI-STORAGE-006E). */
    val derivedObjects: Long = 0,
    /** `base_objects + Σ delta.objects` of the scope. */
    val accountedObjects: Long = 0,
) {
    /** Bytes decide; when they agree, the object count does. UNDER is the dangerous direction for both. */
    val direction: DriftDirection
        get() =
            when {
                accountedBytes < derivedBytes -> DriftDirection.UNDER
                accountedBytes > derivedBytes -> DriftDirection.OVER
                accountedObjects < derivedObjects -> DriftDirection.UNDER
                else -> DriftDirection.OVER
            }
}

/**
 * Which way the ledger was wrong. `UNDER` is the dangerous direction for
 * ADR-035, because a later admission would believe there was room.
 */
enum class DriftDirection { UNDER, OVER }
