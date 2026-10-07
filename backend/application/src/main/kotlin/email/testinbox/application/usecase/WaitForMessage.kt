package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.CorruptStorageStateException
import email.testinbox.application.port.InboxObservation
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.WaitHandle
import email.testinbox.application.port.WaitMetrics
import email.testinbox.application.port.WaitObservations
import email.testinbox.application.port.WaitOutcome
import email.testinbox.application.port.WaitSlots
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.MessageMatcher
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageRefusalReason
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Test-only synchronization hook — lets deterministic tests interleave a
 * message arrival or a storage refusal with the check/subscribe/recheck
 * sequence (ADR-012/020, ADR-035 §13c) without sleeps. The production hook is
 * a no-op, and no endpoint, property or flag reaches it.
 */
interface WaitSyncHook {
    fun afterInitialCheck(inboxId: InboxId) {}

    fun afterSubscribe(inboxId: InboxId) {}

    /**
     * The result is decided and its snapshot closed; nothing has been
     * rendered yet. A refusal committed here must not appear in the response
     * (ADR-035 §13c: the echo comes from the deciding snapshot, never a fresh
     * read).
     */
    fun afterDecision(inboxId: InboxId) {}

    companion object {
        val NOOP: WaitSyncHook = object : WaitSyncHook {}
    }
}

/**
 * The core wait primitive (docs/architecture/wait-semantics.md):
 * check → subscribe → recheck → park; non-consuming; earliest match wins;
 * window expiry is a successful TIMEOUT result with diagnostics, never an
 * HTTP error (ADR-020).
 *
 * ADR-035 §13c adds one opt-in outcome. Every evaluation reads the inbox's
 * visible messages and its storage refusal record from ONE database snapshot
 * ([WaitObservations]) and decides, in this order:
 *
 * 1. a matching visible message → MATCHED (a match always wins);
 * 2. otherwise, if the caller supplied `afterStorageRefusalCount` and the
 *    snapshot's `refusal_count` exceeds the effective boundary →
 *    STORAGE_LIMIT_EXCEEDED, decided before any wait slot is claimed;
 * 3. otherwise continue: subscribe, re-check, park, re-evaluate on every wake,
 *    and TIMEOUT at the window's end from the final evaluation's snapshot.
 *
 * The effective boundary is `min(cursor, refusal_count at the first
 * evaluation)`, computed ONCE and carried for the whole call. Re-clamping it
 * against each later count would let a future-valued cursor suppress a real
 * refusal forever. A call that omits the cursor can never see the new
 * outcome: it ends MATCHED or TIMEOUT exactly as before ADR-035, and TIMEOUT
 * merely carries the count for diagnosis.
 *
 * The server keeps no per-client cursor. The request carries it.
 */
@Suppress("LongParameterList") // one collaborator per step of the protocol, plus the policy the 409 body names; see the class comment
class WaitForMessage(
    private val inboxes: InboxRepository,
    private val observations: WaitObservations,
    private val notifier: MessageNotifier,
    private val waitSlots: WaitSlots,
    private val maxConcurrentWaits: Long,
    private val clock: Clock,
    private val config: TestInboxConfig,
    /** The same effective policy T1 admits against: the `409` body names ITS limits. */
    private val policy: StorageCapacityPolicy,
    private val hook: WaitSyncHook = WaitSyncHook.NOOP,
    private val metrics: WaitMetrics = WaitMetrics.NOOP,
) {
    data class Command(
        val workspaceId: WorkspaceId,
        val inboxId: InboxId,
        val matcher: MessageMatcher,
        val timeoutSeconds: Long,
        /**
         * ADR-035 §13c observation boundary. Null means the legacy contract:
         * storage refusals are never surfaced. Zero means "every refusal since
         * the inbox was created", which is a different request, so absence is
         * never defaulted to zero.
         */
        val afterStorageRefusalCount: Long? = null,
    )

    /** The two tenant-scope figures a `409` may name (ADR-035 §13c); never a global one (§13d). */
    data class TenantScopeFigures(
        val limitBytes: Long,
        /** `stored + reserved` for the named scope. */
        val currentBytes: Long,
    )

    sealed interface Result {
        data class Matched(
            val message: Message,
            val elapsedMs: Long,
            /** From the snapshot that held the match, never a later read. */
            val refusals: StorageRefusalSnapshot,
        ) : Result

        data class Timeout(
            val elapsedMs: Long,
            val arrivedButUnmatchedCount: Int,
            val parseFailedCount: Int,
            /** From the final evaluation's snapshot, the same one that produced the diagnostics. */
            val refusals: StorageRefusalSnapshot,
        ) : Result

        /**
         * ADR-035 §13c: no match, and the inbox refused a copy after the
         * caller's observation boundary. Maps to `409 storage-limit-exceeded`.
         * Everything the adapter renders is here, so it never reads again.
         */
        data class StorageLimitExceeded(
            val inboxId: InboxId,
            /** The effective boundary this call evaluated against: `min(cursor, count at the first evaluation)`. */
            val afterStorageRefusalCount: Long,
            val refusals: StorageRefusalSnapshot,
            val refusalReason: StorageRefusalReason,
            /** Present for `INBOX_LIMIT` and `WORKSPACE_LIMIT`; null for `SERVICE_CAPACITY`, which is one bit. */
            val tenantScope: TenantScopeFigures?,
        ) : Result

        /** Inbox exists but is no longer ACTIVE — maps to 410 Gone (ADR-020). */
        data object InboxGone : Result

        data object InboxNotFound : Result

        /**
         * The workspace already holds every concurrent-wait slot. Rate-shaped,
         * not state-shaped: a slot frees with time, so this maps to 429 with a
         * Retry-After rather than 409 (ADR-027 §8).
         */
        data class ConcurrentWaitLimitExceeded(
            val limit: Long,
        ) : Result

        data class InvalidRequest(
            val reason: String,
        ) : Result
    }

    /**
     * Measured around the whole call rather than at each return, because there
     * are eight of them and the ninth would be the one nobody instruments.
     * `System.nanoTime` rather than the injected clock: this is a wall-clock
     * duration, and a test clock that does not advance would otherwise record
     * every wait as instantaneous.
     */
    fun execute(command: Command): Result {
        val startedAt = System.nanoTime()
        metrics.waitStarted()
        var outcome = WaitOutcome.ERROR
        try {
            val result = executeInternal(command)
            hook.afterDecision(command.inboxId)
            outcome = result.outcome()
            return result
        } finally {
            metrics.waitCompleted(outcome, Duration.ofNanos(System.nanoTime() - startedAt))
        }
    }

    private fun Result.outcome(): WaitOutcome =
        when (this) {
            is Result.Matched -> WaitOutcome.MATCHED
            is Result.Timeout -> WaitOutcome.TIMEOUT
            is Result.StorageLimitExceeded -> WaitOutcome.STORAGE_LIMIT_EXCEEDED
            Result.InboxGone -> WaitOutcome.INBOX_GONE
            Result.InboxNotFound -> WaitOutcome.INBOX_NOT_FOUND
            is Result.ConcurrentWaitLimitExceeded -> WaitOutcome.WAIT_LIMIT_EXCEEDED
            is Result.InvalidRequest -> WaitOutcome.INVALID_REQUEST
        }

    /** What one evaluation saw: enough for a match, a refusal, or the final diagnostics. */
    private class Snapshot(
        val messages: List<Message>,
        val refusals: StorageRefusalSnapshot,
    )

    private sealed interface Evaluation {
        val snapshot: Snapshot

        class Match(
            val message: Message,
            override val snapshot: Snapshot,
        ) : Evaluation

        class Refused(
            val result: Result.StorageLimitExceeded,
            override val snapshot: Snapshot,
        ) : Evaluation

        class Pending(
            override val snapshot: Snapshot,
        ) : Evaluation
    }

    /** One call's state: its effective boundary, fixed at the first evaluation. */
    private inner class Wait(
        val command: Command,
    ) {
        private var boundaryFixed = false
        private var boundary: Long? = null

        /** ONE snapshot: messages, refusal record and, if a 409 is being decided, the figures its body names. */
        fun evaluate(): Evaluation =
            observations.observe(command.workspaceId, command.inboxId) { observed ->
                if (!boundaryFixed) {
                    // ADR-035 §13c: a cursor above the count is treated as the
                    // count, ONCE. Re-clamping at every evaluation would turn a
                    // cursor of 100 against a count of 5, then 6, into a
                    // boundary of 6, and silence that refusal forever.
                    boundary = command.afterStorageRefusalCount?.let { minOf(it, observed.refusals.count) }
                    boundaryFixed = true
                }
                val snapshot = Snapshot(observed.messages, observed.refusals)
                val match = observed.messages.firstOrNull { command.matcher.matches(it) }
                val observedBoundary = boundary
                when {
                    match != null -> {
                        Evaluation.Match(match, snapshot)
                    }

                    observedBoundary != null && observed.refusals.count > observedBoundary -> {
                        Evaluation.Refused(storageLimitExceeded(observed, observedBoundary), snapshot)
                    }

                    else -> {
                        Evaluation.Pending(snapshot)
                    }
                }
            }

        /** Reads the tenant-scope figures INSIDE the observation, so the 409 body comes from the deciding snapshot. */
        private fun storageLimitExceeded(
            observed: InboxObservation,
            boundary: Long,
        ): Result.StorageLimitExceeded {
            val reason =
                observed.refusals.lastReason
                    ?: throw CorruptStorageStateException("refusal_count is ${observed.refusals.count} but no refusal reason is recorded")
            val scope =
                when (reason) {
                    StorageRefusalReason.INBOX_LIMIT -> {
                        TenantScopeFigures(policy.inboxLimitBytes, observed.storage().inbox.usedBytes)
                    }

                    StorageRefusalReason.WORKSPACE_LIMIT -> {
                        TenantScopeFigures(policy.workspaceLimitBytes, observed.storage().workspace.usedBytes)
                    }

                    // One bit (ADR-035 §13d): no limit, no current, no global figure of any kind.
                    StorageRefusalReason.SERVICE_CAPACITY -> {
                        null
                    }
                }
            return Result.StorageLimitExceeded(
                inboxId = command.inboxId,
                afterStorageRefusalCount = boundary,
                refusals = observed.refusals,
                refusalReason = reason,
                tenantScope = scope,
            )
        }
    }

    private fun executeInternal(command: Command): Result {
        if (command.timeoutSeconds <= 0) return Result.InvalidRequest("timeoutSeconds must be positive")
        command.afterStorageRefusalCount?.let {
            if (it < 0) return Result.InvalidRequest("afterStorageRefusalCount must not be negative")
        }
        val start = clock.instant()
        val window = minOf(Duration.ofSeconds(command.timeoutSeconds), config.waitWindowCap)
        val deadline = start.plus(window)

        val inbox =
            inboxes.findById(command.workspaceId, command.inboxId) ?: return Result.InboxNotFound
        if (!inbox.acceptsWaiters()) return Result.InboxGone

        val wait = Wait(command)
        // (a) initial evaluation: an already-visible match, or an already-visible refusal.
        var last = wait.evaluate()
        last.terminal(start)?.let { return it }
        hook.afterInitialCheck(command.inboxId)

        // (b) subscribe before anything else observable, then (c) re-evaluate, then (d) park.
        notifier.subscribe(command.inboxId).use { handle ->
            hook.afterSubscribe(command.inboxId)
            last = wait.evaluate()
            last.terminal(start)?.let { return it }
            if (clock.instant().isBefore(deadline)) {
                when (val parked = parkUntil(wait, handle, start, deadline)) {
                    is Parked.Decided -> return parked.result
                    is Parked.Expired -> last = parked.last
                }
            }
        }

        // TIMEOUT and its diagnostics come from the FINAL evaluation's snapshot
        // (ADR-035 §13c): the same rows decided "no match" and are echoed.
        val arrivedInWindow = last.snapshot.messages.filter { !it.receivedAt.isBefore(start) }
        return Result.Timeout(
            elapsedMs = elapsedMs(start),
            arrivedButUnmatchedCount =
                arrivedInWindow.count { it.parseStatus == ParseStatus.OK && !command.matcher.matches(it) },
            parseFailedCount = arrivedInWindow.count { it.parseStatus == ParseStatus.FAILED },
            refusals = last.snapshot.refusals,
        )
    }

    private fun Evaluation.terminal(start: Instant): Result? =
        when (this) {
            is Evaluation.Match -> Result.Matched(message, elapsedMs(start), snapshot.refusals)
            is Evaluation.Refused -> result
            is Evaluation.Pending -> null
        }

    private sealed interface Parked {
        class Decided(
            val result: Result,
        ) : Parked

        /** The window expired; [last] is the evaluation taken at the deadline. */
        class Expired(
            val last: Evaluation,
        ) : Parked
    }

    /**
     * Claims a concurrency slot and blocks until a match, a refusal or the
     * deadline.
     *
     * The slot is claimed only here — once the call is genuinely going to park
     * (ADR-027 §7) — so a wait that was already satisfiable, or already
     * refused, is never charged for capacity it does not consume. A refusal
     * that arrives while parked wakes the waiter through the inbox's own
     * notification channel (ADR-035 §6a), is found by the re-evaluation, and
     * releases the slot like any other outcome.
     */
    private fun parkUntil(
        wait: Wait,
        handle: WaitHandle,
        start: Instant,
        deadline: Instant,
    ): Parked {
        val slot =
            waitSlots.acquire(
                workspaceId = wait.command.workspaceId,
                maxConcurrent = maxConcurrentWaits,
                // Outlive the window so a crashed node cannot leak the slot,
                // but not so far that recovery is slow. The database turns this
                // into a deadline, so node clock skew cannot free a live slot.
                leaseFor = Duration.between(clock.instant(), deadline).plus(config.waitWindowCap),
            ) ?: run {
                metrics.slotRejected()
                return Parked.Decided(Result.ConcurrentWaitLimitExceeded(maxConcurrentWaits))
            }
        metrics.slotsChanged(1)
        return slot.use {
            try {
                var evaluation = wait.evaluate()
                // At the deadline the latest evaluation IS the final snapshot:
                // no further read happens between it and the response.
                while (evaluation.terminal(start) == null && clock.instant().isBefore(deadline)) {
                    handle.awaitWake(deadline)
                    evaluation = wait.evaluate()
                }
                evaluation.terminal(start)?.let { Parked.Decided(it) } ?: Parked.Expired(evaluation)
            } finally {
                // Mirrors the slot release, including on an early match and on
                // any exception, so the gauge cannot drift upward.
                metrics.slotsChanged(-1)
            }
        }
    }

    private fun elapsedMs(start: Instant): Long = Duration.between(start, clock.instant()).toMillis()
}
