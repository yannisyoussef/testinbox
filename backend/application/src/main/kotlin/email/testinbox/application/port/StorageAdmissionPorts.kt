package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageUsage
import java.time.Instant
import java.util.UUID

/**
 * ADR-035 T1: the atomic admission transaction (§4).
 *
 * [admit] runs, in one transaction:
 * 1. READ COMMITTED, `synchronous_commit = on`, and a lock timeout;
 * 2. the global admission lock, `pg_advisory_xact_lock(35, 1)`;
 * 3. ONE statement that reads every figure the decision needs
 *    ([StorageUsageSnapshot]);
 * 4. [decide], which runs inside the transaction and holds the lock;
 * 5. one insert of the reservations [decide] returned, then commit.
 *
 * If [decide] throws, or the insert fails, nothing is committed.
 *
 * TI-STORAGE-002: nothing on a live path calls this yet.
 */
interface StorageAdmissionStore {
    fun <R : Any> admit(
        scope: StorageAdmissionScope,
        decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
    ): R
}

/** The workspaces and inboxes whose figures one T1 must read. */
data class StorageAdmissionScope(
    val workspaceIds: Set<WorkspaceId>,
    val inboxIds: Set<InboxId>,
) {
    init {
        require(workspaceIds.isNotEmpty() && inboxIds.isNotEmpty()) { "an admission scope reads at least one inbox" }
    }
}

/**
 * Everything the decision reads, from ONE database snapshot (ADR-035 §4).
 *
 * T2 moves reserved bytes to the ledger, and the compactor moves ledger deltas
 * into base rows. Each move is atomic. One statement sees each move either
 * wholly or not at all. Two statements could see a move the first one missed,
 * and under-count exactly those bytes. That is why this is one snapshot.
 */
data class StorageUsageSnapshot(
    /** The database clock (`now()`) of the admission transaction. */
    val t0: Instant,
    val global: StorageUsage,
    val workspaces: Map<WorkspaceId, StorageUsage>,
    val inboxes: Map<InboxId, InboxStorageUsage>,
    /**
     * The footprint inputs read in the SAME statement (contract §2.4 "one
     * snapshot"): deletion debt, the newest observation, the watermark and
     * the trust marker. Null when the store does not read them.
     */
    val footprint: ObservedFootprint? = null,
)

/**
 * The contract §5.3 debt sums and the newest observation as T1 reads them.
 * [debtBytes]/[debtObjects]: every pending row plus every row ordered at or
 * after the newest observation's start (all rows when there is none).
 */
data class ObservedFootprint(
    /** *L*: committed plus reserved, bytes and objects, UNCLAMPED — a negative figure is corruption (INDETERMINATE). */
    val liveBytes: Long,
    val liveObjects: Long,
    val debtBytes: Long,
    val debtObjects: Long,
    val countsTrusted: Boolean,
    val compactedThroughSeq: Long,
    /** The newest observation (by `started_seq`), or nulls when none exists. */
    val startedSeq: Long?,
    val trashBytes: Long?,
    val blockSizeBytes: Long?,
    val capacityBytes: Long?,
    val writtenBy: String?,
    /** The order of the last distrust event (V9): the newest observation must have begun after it (contract §4.5). */
    val distrustedSeq: Long = 0,
)

/**
 * An inbox's usage, plus the workspace that owns it now, or null if it no
 * longer exists. Admission uses the owner to refuse inconsistent input.
 */
data class InboxStorageUsage(
    val owner: WorkspaceId?,
    val usage: StorageUsage,
)

/** What [StorageAdmissionStore.admit] inserts, and what it returns. */
data class StorageAdmissionPlan<R : Any>(
    val reservations: List<StorageReservationDraft>,
    val outcome: R,
)

/**
 * One `storage_reservation` row, as T1 creates it: `RESERVED`, with no
 * upload started and no release scheduled.
 */
data class StorageReservationDraft(
    val messageId: MessageId,
    val workspaceId: WorkspaceId,
    val inboxId: InboxId,
    val objectKeys: List<String>,
    val bytes: Long,
    val createdAt: Instant,
    val writeDeadlineAt: Instant,
    val nodeId: String,
    val generation: UUID,
) {
    init {
        require(bytes > 0) { "a reservation holds a positive number of bytes, was $bytes" }
        require(objectKeys.isNotEmpty()) { "a reservation names its exact keys" }
        require(writeDeadlineAt.isAfter(createdAt)) { "the write deadline follows t0" }
    }
}

/**
 * T1 could not decide, because the admission lock was not granted within the
 * lock timeout. The whole event is refused as an infrastructure failure, the
 * later live path's `451` (ADR-035 §4), and nothing was reserved.
 *
 * It is NOT `SERVICE_CAPACITY`. A slow database says nothing about capacity.
 */
class StorageAdmissionUnavailableException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/**
 * The caller supplied ownership the database contradicts: an inbox that does
 * not exist, or one that belongs to another workspace. That is a programming
 * error, and T1 fails closed: nothing is reserved.
 */
class StorageAdmissionInputException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Checked arithmetic overflowed while projecting usage. That is corrupt
 * accounting or configuration, never capacity, so T1 fails closed.
 */
class StorageAccountingOverflowException(
    message: String,
    cause: ArithmeticException,
) : IllegalStateException(message, cause)
