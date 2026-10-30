package email.testinbox.application.port

import email.testinbox.domain.InboxId
import java.time.Instant

/** Postgres NOTIFY channel shared by the persistence (sender) and notification (listener) adapters. */
const val NOTIFICATION_CHANNEL: String = "testinbox_messages"

/**
 * The notification payload: a wake-up hint, never the event (ADR-020 §1).
 * `<workspaceId>/<inboxId>` since the workspace event stream exists, so a
 * listener can wake exactly the workspace an event belongs to — a stream
 * woken by every tenant's activity would make foreign notifications
 * observable as timing (ADR-025: a recipient-existence oracle). The legacy
 * shape, a bare inbox id, is still decoded (a node from before this change
 * may emit it during a rollout) and wakes that inbox's waiters only.
 */
object NotificationPayload {
    fun encode(
        workspaceId: email.testinbox.domain.WorkspaceId,
        inboxId: InboxId,
    ): String = "${workspaceId.value}/${inboxId.value}"

    data class Decoded(
        val workspaceId: email.testinbox.domain.WorkspaceId?,
        val inboxId: InboxId,
    )

    /** Null when the payload is neither shape (a foreign process on the channel). */
    fun decode(raw: String?): Decoded? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('/')
        return runCatching {
            when (parts.size) {
                1 -> {
                    Decoded(null, InboxId(java.util.UUID.fromString(parts[0])))
                }

                2 -> {
                    Decoded(
                        email.testinbox.domain.WorkspaceId(java.util.UUID.fromString(parts[0])),
                        InboxId(java.util.UUID.fromString(parts[1])),
                    )
                }

                else -> {
                    null
                }
            }
        }.getOrNull()
    }
}

enum class WakeOutcome {
    /** Woken by a notification, a reconnect-epoch bump, or a degraded-mode tick — re-query. */
    WOKEN,

    /** The deadline passed with no wake. */
    DEADLINE,
}

interface WaitHandle : AutoCloseable {
    /** Parks until woken or [deadline]; spurious wakes are allowed (callers always re-query, ADR-020). */
    fun awaitWake(deadline: Instant): WakeOutcome

    override fun close()
}

data class NotifierHealth(
    /** True while the session-scoped LISTEN connection is confirmed active. */
    val listening: Boolean,
    /** Incremented on every successful (re-)LISTEN — parked waiters re-query on epoch change. */
    val epoch: Long,
    val reconnectCount: Long,
)

/**
 * Wait-notification fan-out (ADR-007/ADR-020). Implementations must:
 * park waiters only after subscription registration; on LISTEN loss,
 * reconnect with backoff and wake every parked waiter after re-LISTEN;
 * degrade to a bounded-interval wake while disconnected; surface health.
 */
interface MessageNotifier {
    fun subscribe(inboxId: InboxId): WaitHandle

    /**
     * Woken by every notification carrying [workspaceId] — a message, a
     * transition or a refusal in any of its inboxes — plus the same reconnect
     * and degraded wakes as [subscribe]. Never by another workspace's
     * notification: a holder that woke on foreign activity would expose it
     * as timing (ADR-025), so the wake itself is scoped, not just the read.
     */
    fun subscribeWorkspace(workspaceId: email.testinbox.domain.WorkspaceId): WaitHandle

    fun health(): NotifierHealth
}
