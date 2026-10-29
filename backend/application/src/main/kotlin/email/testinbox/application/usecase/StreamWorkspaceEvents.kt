package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.InboxCursor
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitSlot
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.limits.RateCategory
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
 * Built on the same mechanics as a wait (ADR-012/020): the stream parks on a
 * workspace-scoped subscription and **re-queries its own workspace on every
 * wake** — the notification payload is only a hint, never the event itself.
 * The wake is scoped too: another workspace's activity never wakes this
 * stream, so it cannot be observed as timing (ADR-025 would otherwise be
 * reopened through the API).
 *
 * **Why the read is an overlap, not a strict cursor.** `receivedAt` is stamped
 * by the ingestion node when `DATA` completes, *before* the raw upload and
 * the commit (ADR-005 raw-first), so a message can become visible with a
 * `receivedAt` older than one already delivered — by the whole pipeline
 * latency plus clock skew. A read strictly after the last delivered position
 * would skip it forever. Instead every wake re-reads everything received
 * within [visibilityLag] of the newest position and emits what it has not
 * emitted yet, deduplicated by message id. A message is therefore never
 * missed if it commits within [visibilityLag] of its receipt; [visibilityLag]
 * must exceed the ingestion pipeline's receipt-to-commit bound (the raw
 * upload alone is capped at 30 s and a copy's objects upload one after
 * another, so the typical bound, not a derived worst case; the deployment
 * declares it and `DeploymentSafety` refuses one below the protocol's
 * `T_PUT + EPSILON_MAX`). Events are therefore in *visibility*
 * order: a later event may carry an older `receivedAt` than one already
 * delivered, and the position only ever advances. A **resumed** window re-reads the same
 * horizon behind the client's position, so it may repeat events the client
 * already saw: delivery is at-least-once across windows and exactly-once
 * within one; clients deduplicate on the message id.
 *
 * A backlog — a resumed position far behind, or a burst — is read **one page
 * per call** and the stream simply does not park while a page was full, so
 * no call ever holds more than a page of rows: a client cannot make the
 * server load a workspace's whole history into memory by resuming from the
 * epoch — and each continuation page spends a `READ` token (ADR-027), so
 * the walk is paced by the same budget as `GET /v1/messages`; when the
 * bucket is empty the stream parks and reads its next page on the next wake
 * or scan tick instead.
 *
 * Inbox lifecycle is a periodic diff of **all** the workspace's rows
 * (EXPIRING is not notified; EXPIRED and DELETED are, since issue #87).
 *
 * A stream holds a concurrent-wait slot for its whole window (ADR-027 §7):
 * it is a parked connection, charged and metered like one. The window is
 * capped at `waitWindowCap`, like a single wait call; the client reconnects.
 */
@Suppress("LongParameterList") // one collaborator per step of the wait protocol, plus the two horizons; see the class comment
class StreamWorkspaceEvents(
    private val inboxes: InboxRepository,
    private val messages: MessageRepository,
    private val notifier: MessageNotifier,
    private val waitSlots: WaitSlots,
    private val maxConcurrentWaits: Long,
    private val clock: Clock,
    private val config: TestInboxConfig,
    /** Charges a backlog's continuation pages as `READ`s, so a resume from far back paces itself like the list would. */
    private val rateLimiter: RateLimiter,
    private val metrics: WaitMetrics = WaitMetrics.NOOP,
    /** How often the inbox lifecycle is re-read when nothing wakes the stream. */
    private val inboxScanInterval: Duration = Duration.ofSeconds(2),
    /** How far behind the newest position a wake re-reads: `TestInboxConfig.eventsVisibilityLag`, a deployment invariant. */
    private val visibilityLag: Duration = config.eventsVisibilityLag,
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
     * Opens a window. [after] resumes from a position the client already
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
                ?: run {
                    metrics.slotRejected()
                    return Open.ConcurrentWaitLimitExceeded(maxConcurrentWaits)
                }
        metrics.slotsChanged(1)
        return try {
            // Subscribe BEFORE the first read (check → subscribe → recheck): a
            // message committed between the two is caught by the recheck, never lost.
            val handle = notifier.subscribeWorkspace(workspaceId)
            try {
                Open.Opened(Stream(workspaceId, slot, handle, after, now))
            } catch (e: RuntimeException) {
                // Initialization reads inboxes and messages after subscribing.
                // If either read fails, no Stream reaches the caller to close
                // this handle. Preserve the read failure if cleanup fails too.
                runCatching { handle.close() }.exceptionOrNull()?.let(e::addSuppressed)
                throw e
            }
        } catch (e: RuntimeException) {
            release(slot)
            throw e
        }
    }

    private fun release(slot: WaitSlot) {
        slot.close()
        metrics.slotsChanged(-1)
    }

    inner class Stream internal constructor(
        private val workspaceId: WorkspaceId,
        private val slot: WaitSlot,
        private val handle: WaitHandle,
        after: MessageCursor?,
        opened: Instant,
    ) : AutoCloseable {
        /** The window's end; the holder ends the stream here and the client reconnects. */
        val deadline: Instant = opened.plus(config.waitWindowCap)

        /** The newest position delivered or started from; what a client resumes from after this window. */
        var position: MessageCursor = after ?: MessageCursor(receivedAt = opened, id = MessageId(UUID(0L, 0L)))
            private set

        /** Messages already emitted — or visible before the window opened — whose `receivedAt` is still inside the horizon. */
        private val seen = HashMap<MessageId, Instant>()
        private var known: Map<InboxId, Inbox> = snapshotInboxes()
        private var nextInboxScan: Instant = opened.plus(inboxScanInterval)
        private var first = true

        /** Where an unfinished page walk continues; null when the last page read was short. */
        private var walk: MessageCursor? = null

        init {
            // A fresh window starts "from now": what is visible already is not
            // replayed, so it is marked seen without being emitted — ids only,
            // a page at a time. A resumed window seeds nothing: the client's
            // horizon is re-delivered.
            if (after == null) {
                do {
                    drainPage().forEach { seen[it.id] = it.receivedAt }
                } while (walk != null)
            }
        }

        /**
         * Everything that happened since the last call, blocking until a wake
         * or [until] (whichever first) when nothing is pending. The first call
         * never blocks, and neither does a call while a backlog page is still
         * pending: a resumed position's backlog streams out at once, a page a
         * call.
         */
        fun next(until: Instant): List<WorkspaceEvent> {
            var woken = false
            if (first || continuationAllowed()) {
                first = false
            } else {
                // The park is capped at the next lifecycle scan as well as the
                // caller's deadline: creation and EXPIRING wake nothing, so a
                // park that only ended on a wake would report them at the
                // window's end — which foreign wakes used to hide.
                woken = handle.awaitWake(minOf(until, deadline, nextInboxScan)) == WakeOutcome.WOKEN
            }
            val events = mutableListOf<WorkspaceEvent>()
            // Read on every call, woken or not: during a mixed-version rollout
            // an older node's bare-id payload wakes no stream, and the scan
            // tick is then what delivers its messages. Narrowing this to
            // wakes only is a change to make together with retiring the
            // legacy payload.
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

        /** A pending page walk continues without parking only while the READ budget admits another page. */
        private fun continuationAllowed(): Boolean = walk != null && rateLimiter.tryConsume(workspaceId, RateCategory.READ).allowed

        private fun newMessages(): List<WorkspaceEvent> {
            val fresh = drainPage().filter { it.id !in seen }
            fresh.forEach { seen[it.id] = it.receivedAt }
            return fresh.map { WorkspaceEvent.MessageReceived(it) }
        }

        /**
         * One page of the messages received within the horizon, oldest first,
         * continuing an unfinished walk; a full page leaves [walk] set so the
         * next call reads on without parking. The horizon moves with the
         * newest row seen, and `seen` is pruned to it.
         */
        private fun drainPage(): List<Message> {
            val from = walk ?: MessageCursor(position.receivedAt.minus(visibilityLag), MessageId(UUID(0L, 0L)))
            val page = messages.listWorkspacePage(workspaceId, from, MESSAGE_PAGE, descending = false)
            walk = if (page.size < MESSAGE_PAGE) null else MessageCursor(page.last().receivedAt, page.last().id)
            page.lastOrNull()?.let { newest ->
                val candidate = MessageCursor(newest.receivedAt, newest.id)
                if (candidate.receivedAt.isAfter(position.receivedAt)) position = candidate
            }
            val floor = position.receivedAt.minus(visibilityLag)
            seen.values.removeIf { it.isBefore(floor) }
            return page
        }

        private fun inboxChanges(): List<WorkspaceEvent> {
            val current = snapshotInboxes()
            val events = mutableListOf<WorkspaceEvent>()
            for ((id, inbox) in current) {
                val before = known[id]
                when {
                    before == null -> {
                        if (inbox.state == InboxState.ACTIVE) {
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

        /** Every row of the workspace, however many: a bounded snapshot would report its own edge as `gone`/`created`. */
        private fun snapshotInboxes(): Map<InboxId, Inbox> {
            val rows = LinkedHashMap<InboxId, Inbox>()
            var before: InboxCursor? = null
            while (true) {
                val page = inboxes.listPage(workspaceId, emptySet(), before, INBOX_PAGE)
                page.forEach { rows[it.id] = it }
                if (page.size < INBOX_PAGE) break
                before = InboxCursor(page.last().createdAt, page.last().id)
            }
            return rows
        }

        override fun close() {
            try {
                handle.close()
            } finally {
                release(slot)
            }
        }
    }

    private companion object {
        const val MESSAGE_PAGE = 200
        const val INBOX_PAGE = 500
    }
}
