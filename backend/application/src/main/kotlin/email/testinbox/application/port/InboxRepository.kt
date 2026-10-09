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

interface InboxRepository {
    fun insert(inbox: Inbox): InsertInboxOutcome

    fun findById(
        workspaceId: WorkspaceId,
        id: InboxId,
    ): Inbox?

    /**
     * Resolves a recipient address to its routable inbox (state ACTIVE or
     * EXPIRING) — at most one such row exists per address by constraint.
     */
    fun findReceivableByAddress(address: String): Inbox?

    /** Marks the inbox DELETED; returns the prior state's inbox, or null when absent in this workspace. */
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
