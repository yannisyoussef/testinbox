package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import java.time.Instant

sealed interface AppendOutcome {
    data object Appended : AppendOutcome

    /** Same provider delivery event already recorded — reprocessing no-op (ADR-019). */
    data object DuplicateProviderEvent : AppendOutcome
}

data class MessageCursor(
    val receivedAt: Instant,
    val id: MessageId,
)

interface MessageRepository {
    /**
     * Persists the message (with attachment metadata) as Visible and issues
     * `pg_notify` **in the same database transaction** — the ADR-020
     * atomicity invariant. Implementations must never commit the row and
     * notify separately.
     */
    fun appendVisible(message: Message): AppendOutcome

    /** Earliest message in the inbox sharing [fingerprint], for the ADR-019 duplicate annotation. */
    fun findEarliestIdByFingerprint(
        inboxId: InboxId,
        fingerprint: String,
    ): MessageId?

    /** All visible messages of the inbox, earliest first (receipt order). */
    fun listVisible(inboxId: InboxId): List<Message>

    fun listPage(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
        after: MessageCursor?,
        limit: Int,
    ): List<Message>

    /**
     * One page across every inbox of the workspace that still serves reads —
     * the servability rule of `InboxQueries.servable` (ADR-035 Amendment 2:
     * never EXPIRED or DELETED), which the adapter's statement restates as a
     * state filter and must keep in step. Ascending by `(receivedAt, id)`
     * strictly after [cursor], or descending strictly before it, so the same
     * cursor shape walks either way.
     */
    fun listWorkspacePage(
        workspaceId: WorkspaceId,
        cursor: MessageCursor?,
        limit: Int,
        descending: Boolean,
    ): List<Message>

    fun findById(
        workspaceId: WorkspaceId,
        id: MessageId,
    ): Message?

    /** Existence check across workspaces — used only by the orphan blob sweep. */
    fun exists(id: MessageId): Boolean
}
