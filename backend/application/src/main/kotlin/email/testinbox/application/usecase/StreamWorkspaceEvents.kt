package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WaitSlot
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Message
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What a workspace stream reports. Every event is about the caller's own workspace only. */
sealed interface WorkspaceEvent {
    /** A message became visible (ADR-020 visibility) in one of the workspace's serving inboxes. */
    data class MessageReceived(
        val message: Message,
    ) : WorkspaceEvent

    enum class InboxChange {
        CREATED,
        EXPIRING,
        EXPIRED,
        DELETED,

        /** The row is gone: hard-deleted by the sweep after EXPIRED or DELETED. */
        GONE,
    }

    data class InboxChanged(
        val inbox: Inbox,
        val change: InboxChange,
    ) : WorkspaceEvent
}

/**
 * A bounded window of workspace events (console feed, SDK observers).
 *
 * Built on the same mechanics as a wait (ADR-012/020): the stream parks on
 * the notifier and **re-queries its own workspace on every wake** — the
 * notification payload is only a hint, never the event itself, so a foreign
 * workspace's notification costs this stream one scoped read and leaks
 * nothing. Messages are read strictly after a `(receivedAt, id)` cursor, so
 * a client that reconnects with the last id it saw misses nothing and sees
 * nothing twice. Inbox lifecycle is a periodic diff of the workspace's rows
 * (EXPIRING is not notified; EXPIRED and DELETED are, since issue #87).
 *
 * A stream holds a concurrent-wait slot for its whole window (ADR-027 §3):
 * it is a parked connection, charged like one. The window is capped at
 * `waitWindowCap`, like a single wait call; the client reconnects.
 */
class StreamWorkspaceEvents(
    private val inboxes: InboxRepository,
    private val messages: MessageRepository,
    private val notifier: MessageNotifier,
    private val waitSlots: WaitSlots,
    private val maxConcurrentWaits: Long,
    private val clock: Clock,
    private val config: TestInboxConfig,
    /** How often the inbox lifecycle is re-read; wakes do not shorten it, only messages are read on a wake. */
    private val inboxScanInterval: Duration = Duration.ofSeconds(2),
) {
    sealed interface Open {
        data class Opened(
            val stream: Stream,
        ) : Open

        data class ConcurrentWaitLimitExceeded(
            val limit: Long,
        ) : Open
    }

    /**
     * Opens a window. [after] resumes a message cursor the client already
     * saw (its last event id); null starts from now. Close the stream to
     * release its slot — always, including on a client disconnect.
     */
    fun open(
        workspaceId: WorkspaceId,
        after: MessageCursor?,
    ): Open {
        val now = clock.instant()
        val slot =
            waitSlots.acquire(workspaceId, maxConcurrentWaits, leaseFor = config.waitWindowCap.multipliedBy(2))
                ?: return Open.ConcurrentWaitLimitExceeded(maxConcurrentWaits)
        // Subscribe BEFORE the first read (check → subscribe → recheck): a
        // message committed between the two is caught by the recheck, never lost.
        val handle = notifier.subscribeAll()
        val cursor = after ?: MessageCursor(receivedAt = now, id = MessageId(UUID(0L, 0L)))
        return Open.Opened(Stream(workspaceId, slot, handle, cursor, now))
    }

    inner class Stream internal constructor(
        private val workspaceId: WorkspaceId,
        private val slot: WaitSlot,
        private val handle: WaitHandle,
        private var cursor: MessageCursor,
        opened: Instant,
    ) : AutoCloseable {
        /** The window's end; the holder ends the stream here and the client reconnects. */
        val deadline: Instant = opened.plus(config.waitWindowCap)

        /** Position of the last message event delivered; what a client resumes from. */
        val position: MessageCursor get() = cursor

        private var known: Map<InboxId, Inbox> = snapshotInboxes()
        private var nextInboxScan: Instant = opened.plus(inboxScanInterval)
        private var first = true

        /**
         * Everything that happened since the last call, blocking until a wake
         * or [until] (whichever first) when nothing is pending. The first call
         * never blocks: a resumed cursor's backlog is delivered at once.
         */
        fun next(until: Instant): List<WorkspaceEvent> {
            var woken = false
            if (first) {
                first = false
            } else {
                woken = handle.awaitWake(minOf(until, deadline)) == WakeOutcome.WOKEN
            }
            val events = mutableListOf<WorkspaceEvent>()
            events += newMessages()
            val now = clock.instant()
            // A wake may be a transition (EXPIRED and DELETED notify, issue #87),
            // so the lifecycle is re-read on every wake; creation and EXPIRING
            // notify nothing and are caught by the interval.
            if (woken || !now.isBefore(nextInboxScan)) {
                events += inboxChanges()
                nextInboxScan = now.plus(inboxScanInterval)
            }
            return events
        }

        private fun newMessages(): List<WorkspaceEvent> {
            val page = messages.listWorkspacePage(workspaceId, cursor, MESSAGE_PAGE, descending = false)
            if (page.isNotEmpty()) {
                val last = page.last()
                cursor = MessageCursor(last.receivedAt, last.id)
            }
            return page.map { WorkspaceEvent.MessageReceived(it) }
        }

        private fun inboxChanges(): List<WorkspaceEvent> {
            val current = snapshotInboxes()
            val events = mutableListOf<WorkspaceEvent>()
            for ((id, inbox) in current) {
                val before = known[id]
                when {
                    before == null -> {
                        if (inbox.state ==
                            InboxState.ACTIVE
                        ) {
                            events += WorkspaceEvent.InboxChanged(inbox, WorkspaceEvent.InboxChange.CREATED)
                        }
                    }

                    before.state != inbox.state -> {
                        changeOf(inbox.state)?.let { events += WorkspaceEvent.InboxChanged(inbox, it) }
                    }
                }
            }
            for ((id, inbox) in known) {
                if (id !in current) events += WorkspaceEvent.InboxChanged(inbox, WorkspaceEvent.InboxChange.GONE)
            }
            known = current
            return events
        }

        private fun changeOf(state: InboxState): WorkspaceEvent.InboxChange? =
            when (state) {
                InboxState.EXPIRING -> WorkspaceEvent.InboxChange.EXPIRING
                InboxState.EXPIRED -> WorkspaceEvent.InboxChange.EXPIRED
                InboxState.DELETED -> WorkspaceEvent.InboxChange.DELETED
                InboxState.ACTIVE -> null
            }

        private fun snapshotInboxes(): Map<InboxId, Inbox> =
            inboxes.listPage(workspaceId, emptySet(), before = null, limit = INBOX_SNAPSHOT).associateBy { it.id }

        override fun close() {
            handle.close()
            slot.close()
        }
    }

    private companion object {
        const val MESSAGE_PAGE = 200

        /** Newest inboxes watched for lifecycle changes; a workspace beyond this still gets every message event. */
        const val INBOX_SNAPSHOT = 500
    }
}
