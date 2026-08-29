package email.testinbox.domain.inbox

import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import java.time.Instant

enum class AddressMode { GENERATED, EXACT }

enum class InboxState { ACTIVE, EXPIRING, EXPIRED, DELETED }

data class Inbox(
    val id: InboxId,
    val workspaceId: WorkspaceId,
    val projectId: ProjectId,
    val address: String,
    val addressMode: AddressMode,
    val state: InboxState,
    val createdAt: Instant,
    val expiresAt: Instant,
    /** Set once the inbox enters EXPIRING; in-flight deliveries are honored until then (ADR-009). */
    val graceUntil: Instant? = null,
) {
    /**
     * An inbox can receive mail while ACTIVE, or while EXPIRING within its
     * grace window (honoring in-flight SMTP deliveries, ADR-009).
     */
    fun canReceiveAt(now: Instant): Boolean =
        when (state) {
            InboxState.ACTIVE -> true
            InboxState.EXPIRING -> graceUntil != null && now.isBefore(graceUntil)
            InboxState.EXPIRED, InboxState.DELETED -> false
        }

    /** New waiters are accepted only while ACTIVE (ADR-009/ADR-020: non-active inbox => 410). */
    fun acceptsWaiters(): Boolean = state == InboxState.ACTIVE

    val localPart: String get() = address.substringBefore('@')
}
