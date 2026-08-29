package email.testinbox.application.usecase

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.MessageNotifier
import email.testinbox.application.port.MessageRepository
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.MessageMatcher
import email.testinbox.domain.message.ParseStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Test-only synchronization hook — lets deterministic tests interleave a
 * message arrival with the check/subscribe/recheck sequence (ADR-012/020)
 * without sleeps. The production hook is a no-op.
 */
interface WaitSyncHook {
    fun afterInitialCheck(inboxId: InboxId) {}

    fun afterSubscribe(inboxId: InboxId) {}

    companion object {
        val NOOP: WaitSyncHook = object : WaitSyncHook {}
    }
}

/**
 * The core wait primitive (docs/architecture/wait-semantics.md):
 * check → subscribe → recheck → park; non-consuming; earliest match wins;
 * window expiry is a successful TIMEOUT result with diagnostics, never an
 * HTTP error (ADR-020).
 */
class WaitForMessage(
    private val inboxes: InboxRepository,
    private val messages: MessageRepository,
    private val notifier: MessageNotifier,
    private val clock: Clock,
    private val config: TestInboxConfig,
    private val hook: WaitSyncHook = WaitSyncHook.NOOP,
) {
    data class Command(
        val workspaceId: WorkspaceId,
        val inboxId: InboxId,
        val matcher: MessageMatcher,
        val timeoutSeconds: Long,
    )

    sealed interface Result {
        data class Matched(val message: Message, val elapsedMs: Long) : Result

        data class Timeout(
            val elapsedMs: Long,
            val arrivedButUnmatchedCount: Int,
            val parseFailedCount: Int,
        ) : Result

        /** Inbox exists but is no longer ACTIVE — maps to 410 Gone (ADR-020). */
        data object InboxGone : Result

        data object InboxNotFound : Result

        data class InvalidRequest(val reason: String) : Result
    }

    fun execute(command: Command): Result {
        if (command.timeoutSeconds <= 0) return Result.InvalidRequest("timeoutSeconds must be positive")
        val start = clock.instant()
        val window = minOf(Duration.ofSeconds(command.timeoutSeconds), config.waitWindowCap)
        val deadline = start.plus(window)

        val inbox =
            inboxes.findById(command.workspaceId, command.inboxId) ?: return Result.InboxNotFound
        if (!inbox.acceptsWaiters()) return Result.InboxGone

        // (a) initial check for an already-visible match.
        firstMatch(command)?.let { return Result.Matched(it, elapsedMs(start)) }
        hook.afterInitialCheck(command.inboxId)

        // (b) subscribe before anything else observable, then (c) re-check, then (d) park.
        notifier.subscribe(command.inboxId).use { handle ->
            hook.afterSubscribe(command.inboxId)
            while (true) {
                firstMatch(command)?.let { return Result.Matched(it, elapsedMs(start)) }
                if (!clock.instant().isBefore(deadline)) break
                handle.awaitWake(deadline)
            }
        }

        val arrivedInWindow =
            messages.listVisible(command.inboxId).filter { !it.receivedAt.isBefore(start) }
        return Result.Timeout(
            elapsedMs = elapsedMs(start),
            arrivedButUnmatchedCount =
                arrivedInWindow.count { it.parseStatus == ParseStatus.OK && !command.matcher.matches(it) },
            parseFailedCount = arrivedInWindow.count { it.parseStatus == ParseStatus.FAILED },
        )
    }

    private fun firstMatch(command: Command): Message? =
        messages.listVisible(command.inboxId).firstOrNull { command.matcher.matches(it) }

    private fun elapsedMs(start: Instant): Long = Duration.between(start, clock.instant()).toMillis()
}
