package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.Inbox
import java.time.Instant

sealed interface InsertInboxOutcome {
    data object Inserted : InsertInboxOutcome

    /** The routable-address uniqueness constraint rejected the insert. */
    data object AddressTaken : InsertInboxOutcome
}

/** Position in a newest-first inbox listing: `(createdAt, id)` strictly before it. */
data class InboxCursor(
    val createdAt: Instant,
    val id: InboxId,
)

/*
 * `TooManyFunctions` is suppressed rather than the port split: this is the
 * one persistence port of the inbox aggregate, and every function is one row
 * operation a use case needs (ADR-024: invariant-bearing writes go through
 * exactly one use case, which needs exactly this port). Splitting it by
 * counter would scatter one aggregate's persistence across two interfaces;
 * a read/write split (as `TenantReader` is) is the honest alternative, and
 * the one to take if this port grows again.
 */
@Suppress("TooManyFunctions")
interface InboxRepository {
    fun insert(inbox: Inbox): InsertInboxOutcome

    /**
     * One page of the workspace's inboxes, newest first, every state that
     * still has a row (hard-deleted ones are gone). [states] narrows it;
     * empty means every state. Workspace-scoped by construction.
     */
    fun listPage(
        workspaceId: WorkspaceId,
        states: Set<email.testinbox.domain.inbox.InboxState>,
        before: InboxCursor?,
        limit: Int,
    ): List<Inbox>

    fun findById(
        workspaceId: WorkspaceId,
        id: InboxId,
    ): Inbox?

    /**
     * Resolves a recipient address to its routable inbox (state ACTIVE or
     * EXPIRING) — at most one such row exists per address by constraint.
     */
    fun findReceivableByAddress(address: String): Inbox?

    /**
     * Marks the inbox DELETED; returns the prior state's inbox, or null when absent in this workspace.
     *
     * The adapter must wake the inbox's parked waiters in the SAME transaction as the
     * transition (ADR-020: never notify-after-commit; issue #87), so a waiter's
     * re-evaluation sees the new state and ends `410` instead of sleeping to its window.
     */
    fun markDeleted(
        workspaceId: WorkspaceId,
        id: InboxId,
        now: Instant,
    ): Inbox?

    // Lifecycle sweep (ADR-009). Guarded transitions return false when the state moved concurrently.
    fun findExpiredActive(
        now: Instant,
        limit: Int,
    ): List<Inbox>

    fun transitionToExpiring(
        id: InboxId,
        graceUntil: Instant,
    ): Boolean

    fun findExpiringPastGrace(
        now: Instant,
        limit: Int,
    ): List<Inbox>

    /**
     * EXPIRING -> EXPIRED. Like [markDeleted], a successful transition wakes the inbox's
     * parked waiters in the same transaction (ADR-020, issue #87).
     */
    fun transitionToExpired(id: InboxId): Boolean

    fun findHardDeletable(limit: Int): List<Inbox>

    /** Hard-deletes the inbox row and, by cascade, its messages/attachments metadata. */
    fun hardDelete(id: InboxId)
}

/**
 * Paced retention's view of inboxes (filesystem-containment contract §5.4;
 * TI-STORAGE-006E PR D): teardown in batches of exact message ids, and the
 * instants the pacing and the backlog metric read.
 */
interface InboxTeardown {
    /** Up to [limit] message ids of [inboxId], oldest first: the next teardown batch. */
    fun messageIdsOf(
        inboxId: InboxId,
        limit: Int,
    ): List<MessageId>

    /** Deletes exactly [ids] of [inboxId] (attachments by cascade; the ledger triggers write the debt). */
    fun deleteMessages(
        inboxId: InboxId,
        ids: Collection<MessageId>,
    ): Int

    /** Since when a hard-deletable inbox has waited for teardown: `deleted_at`, else `grace_until`, else `expires_at`. */
    fun teardownWaitingSince(id: InboxId): java.time.Instant?

    /** The oldest such instant over every hard-deletable inbox, or null when none waits. */
    fun oldestTeardownWaitingSince(): java.time.Instant?

    companion object {
        /** No batches: an inbox reads as empty, so teardown is the whole-inbox prefix delete it always was. */
        val NONE: InboxTeardown =
            object : InboxTeardown {
                override fun messageIdsOf(
                    inboxId: InboxId,
                    limit: Int,
                ) = emptyList<MessageId>()

                override fun deleteMessages(
                    inboxId: InboxId,
                    ids: Collection<MessageId>,
                ) = 0

                override fun teardownWaitingSince(id: InboxId): java.time.Instant? = null

                override fun oldestTeardownWaitingSince(): java.time.Instant? = null
            }
    }
}
