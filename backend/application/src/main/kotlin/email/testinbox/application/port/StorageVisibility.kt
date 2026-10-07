package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import java.time.Instant

/**
 * An inbox's ADR-035 §6a refusal record as the tenant may see it (§13b):
 * how many copies a storage ceiling refused, and when and why the last one
 * was. Live read-side state, never part of the `Inbox` aggregate.
 *
 * A count of zero carries no metadata. A positive count always carries both:
 * the persistence adapter fails closed on a row that says otherwise, rather
 * than inventing a reason (`CorruptStorageStateException`).
 */
data class StorageRefusalSnapshot(
    val count: Long,
    val lastAt: Instant?,
    val lastReason: StorageRefusalReason?,
) {
    init {
        require(count >= 0) { "a refusal count is never negative, was $count" }
        if (count == 0L) {
            require(lastAt == null && lastReason == null) { "no refusal has happened, so there is no last refusal" }
        } else {
            require(lastAt != null && lastReason != null) { "a refusal happened, so its time and reason are known" }
        }
    }

    companion object {
        val NONE = StorageRefusalSnapshot(0, null, null)
    }
}

/** One inbox's figures: its committed and reserved bytes, and its refusal record. */
data class InboxStorageFigures(
    val usage: StorageUsage,
    val refusals: StorageRefusalSnapshot,
) {
    companion object {
        /** An inbox with no accounting row, no delta and no reservation. */
        val EMPTY = InboxStorageFigures(StorageUsage.ZERO, StorageRefusalSnapshot.NONE)
    }
}

/**
 * Everything one visibility read returns, from ONE database snapshot: the
 * workspace's own figures and those of the inboxes asked for. An inbox id
 * that is not the workspace's reads as [InboxStorageFigures.EMPTY]: the
 * adapter scopes every row by workspace, so nothing about another tenant's
 * inbox can be learned through here.
 */
data class WorkspaceStorageFigures(
    val workspace: StorageUsage,
    val inboxes: Map<InboxId, InboxStorageFigures>,
) {
    fun inbox(id: InboxId): InboxStorageFigures = inboxes[id] ?: InboxStorageFigures.EMPTY
}

/**
 * The authenticated read side of ADR-035 accounting (§13a, §13b).
 *
 * Every figure is `base + Σdelta` for stored bytes and `Σ reservation.bytes`
 * over EVERY reservation state for reserved bytes, exactly as T1 reads them
 * (§4). The adapter must:
 *
 * - read the whole result in one statement or one snapshot, because T2 and
 *   the compactor move bytes between the three sums concurrently;
 * - read a missing base row as 0 while still summing that scope's deltas and
 *   reservations (no inner join may make them disappear);
 * - take neither the admission nor the ledger advisory lock: a visibility
 *   read must never serialize admission;
 * - derive nothing from object storage, and maintain no counter.
 *
 * It is set-based so that a future inbox list can read a page in one call.
 */
interface StorageVisibility {
    fun read(
        workspaceId: WorkspaceId,
        inboxIds: Set<InboxId>,
    ): WorkspaceStorageFigures
}

/**
 * ONE evaluation of a wait (ADR-035 §13c): the inbox's visible messages, with
 * their attachments, and its refusal record, read from the SAME database
 * snapshot. [storage] reads the inbox's and its workspace's byte figures from
 * that snapshot too; it is called only when a `409` body needs them.
 */
interface InboxObservation {
    /** Visible messages of the inbox, earliest first, attachments materialized. */
    val messages: List<Message>

    val refusals: StorageRefusalSnapshot

    /** The inbox's and its workspace's usage, from this observation's snapshot. */
    fun storage(): TenantStorageFigures
}

/** The two tenant-scope figures a `409` may name. Never a global one (ADR-035 §13d). */
data class TenantStorageFigures(
    val inbox: StorageUsage,
    val workspace: StorageUsage,
)

/**
 * The wait use case's view of the database (ADR-035 §13c, ADR-020).
 *
 * [observe] runs [evaluate] against ONE short snapshot of every row one
 * evaluation needs: the visible messages used for matching, their
 * attachments, the inbox's `refusal_count`, `last_refusal_at` and
 * `last_refusal_reason`, and, on demand, the storage figures of a `409` body.
 * Reading those under separate READ COMMITTED snapshots could see a refusal
 * that was committed after the messages were read, or a message committed
 * after the refusal, and so invert the race the ADR decides by one snapshot.
 *
 * The snapshot is closed when [evaluate] returns. It is never held while the
 * caller subscribes, parks or waits for a notification.
 */
interface WaitObservations {
    fun <R : Any> observe(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        evaluate: (InboxObservation) -> R,
    ): R
}

/**
 * The refusal record contradicts itself: a positive count with no time or
 * reason, or a reason this artifact does not know. That is corrupt internal
 * state, never tenant quota state, so the read fails rather than presenting a
 * made-up reason (TI-STORAGE-004 §26).
 */
class CorruptStorageStateException(
    message: String,
) : IllegalStateException(message)
