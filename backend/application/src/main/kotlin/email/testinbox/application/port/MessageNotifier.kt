package email.testinbox.application.port

import email.testinbox.domain.InboxId
import java.time.Instant

/** Postgres NOTIFY channel shared by the persistence (sender) and notification (listener) adapters. */
const val NOTIFICATION_CHANNEL: String = "testinbox_messages"

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
     * Woken by ANY notification (every inbox, every workspace), plus the same
     * reconnect and degraded wakes as [subscribe]. The payload is only a hint
     * (ADR-020 §1): a holder re-queries its own workspace and nothing else, so
     * the most a foreign notification can cause is one extra scoped read.
     */
    fun subscribeAll(): WaitHandle

    fun health(): NotifierHealth
}
