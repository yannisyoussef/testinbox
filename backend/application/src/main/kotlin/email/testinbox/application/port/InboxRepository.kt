package email.testinbox.application.port

import email.testinbox.domain.InboxId
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

    fun findById(workspaceId: WorkspaceId, id: InboxId): Inbox?

    /**
     * Resolves a recipient address to its routable inbox (state ACTIVE or
     * EXPIRING) — at most one such row exists per address by constraint.
     */
    fun findReceivableByAddress(address: String): Inbox?

    /** Marks the inbox DELETED; returns the prior state's inbox, or null when absent in this workspace. */
    fun markDeleted(workspaceId: WorkspaceId, id: InboxId, now: Instant): Inbox?

    // Lifecycle sweep (ADR-009). Guarded transitions return false when the state moved concurrently.
    fun findExpiredActive(now: Instant, limit: Int): List<Inbox>

    fun transitionToExpiring(id: InboxId, graceUntil: Instant): Boolean

    fun findExpiringPastGrace(now: Instant, limit: Int): List<Inbox>

    fun transitionToExpired(id: InboxId): Boolean

    fun findHardDeletable(limit: Int): List<Inbox>

    /** Hard-deletes the inbox row and, by cascade, its messages/attachments metadata. */
    fun hardDelete(id: InboxId)
}
