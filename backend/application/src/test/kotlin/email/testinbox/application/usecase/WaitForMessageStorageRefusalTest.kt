package email.testinbox.application.usecase

import email.testinbox.application.FakeNotifier
import email.testinbox.application.FakeWaitSlots
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryRefusals
import email.testinbox.application.InMemoryWaitObservations
import email.testinbox.application.MutableClock
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.TenantStorageFigures
import email.testinbox.application.port.WaitOutcome
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.MessageMatcher
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-035 §13c, the server rule, with deterministic interleavings and no
 * sleeps (§17 tests 37, 39–42, 44 at the use-case level; the HTTP, database
 * and notification halves live in the api and persistence suites).
 */
class WaitForMessageStorageRefusalTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private lateinit var inboxes: InMemoryInboxRepository
    private lateinit var messages: InMemoryMessageRepository
    private lateinit var refusals: InMemoryRefusals
    private lateinit var observations: InMemoryWaitObservations
    private lateinit var notifier: FakeNotifier
    private lateinit var clock: MutableClock
    private lateinit var inbox: Inbox
    private lateinit var other: Inbox
    private var hook: WaitSyncHook = WaitSyncHook.NOOP
    private var waitSlots = FakeWaitSlots()
    private var maxConcurrentWaits: Long = 10
    private val metrics = RecordingWaitMetrics()

    /** Workspace 1000, share 0.5: inbox limit 500. */
    private val policy = StorageCapacityPolicy(1_000, InboxShare.of("0.5"), 1L shl 40, 0)
    private val config = TestInboxConfig(mailDomain = "testinbox.local", waitWindowCap = Duration.ofSeconds(60))

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        messages = InMemoryMessageRepository()
        refusals = InMemoryRefusals()
        observations = InMemoryWaitObservations(messages, refusals)
        notifier = FakeNotifier()
        clock = MutableClock(Instant.parse("2026-10-07T12:00:00Z"))
        inbox = newInbox("a@testinbox.local")
        other = newInbox("b@testinbox.local")
        inboxes.insert(inbox)
        inboxes.insert(other)
    }

    private fun newInbox(address: String) =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = workspaceId,
            projectId = ProjectId(UUID.randomUUID()),
            address = address,
            addressMode = AddressMode.GENERATED,
            state = InboxState.ACTIVE,
            createdAt = clock.now,
            expiresAt = clock.now.plusSeconds(600),
        )

    private fun useCase(): WaitForMessage =
        WaitForMessage(inboxes, observations, notifier, waitSlots, maxConcurrentWaits, clock, config, policy, hook, metrics)

    private fun command(
        cursor: Long? = 0,
        matcher: MessageMatcher = MessageMatcher(),
        timeoutSeconds: Long = 10,
        inboxId: InboxId = inbox.id,
    ) = WaitForMessage.Command(workspaceId, inboxId, matcher, timeoutSeconds, afterStorageRefusalCount = cursor)

    private fun refuse(
        reason: StorageRefusalReason = StorageRefusalReason.INBOX_LIMIT,
        inboxId: InboxId = inbox.id,
    ): StorageRefusalSnapshot = refusals.refuse(inboxId, reason, clock.now)

    private fun message(
        subject: String = "hello",
        receivedAt: Instant = clock.now,
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            workspaceId = workspaceId,
            inboxId = inbox.id,
            receivedAt = receivedAt,
            provider = "local-smtp",
            providerMessageId = null,
            envelopeFrom = "sut@example.com",
            envelopeTo = inbox.address,
            rawObjectKey = "k",
            rawSizeBytes = 1,
            contentFingerprint = UUID.randomUUID().toString(),
            possibleDuplicateOfMessageId = null,
            parseStatus = ParseStatus.OK,
            parseError = null,
            parsed = ParsedContent("sut@example.com", null, null, subject, "b", null, emptyList(), emptyList()),
            attachments = emptyList(),
        )

    /** Parks exactly once, then the window expires. */
    private fun expireOnFirstPark() {
        notifier.onAwait = {
            clock.advanceSeconds(11)
            WakeOutcome.DEADLINE
        }
    }

    /** The first wake does [onFirstWake] and reports WOKEN; any later wake ends the window, so a regression fails instead of hanging. */
    private fun onFirstWakeThenExpire(onFirstWake: () -> Unit) {
        var wakes = 0
        notifier.onAwait = {
            if (++wakes == 1) {
                onFirstWake()
                WakeOutcome.WOKEN
            } else {
                clock.advanceSeconds(11)
                WakeOutcome.DEADLINE
            }
        }
    }

    // --- test 37: refusal before the first wait --------------------------------------------

    @Test
    fun `a refusal already visible with cursor 0 is a 409 at once, before subscribing or taking a slot`() {
        val recorded = refuse()
        notifier.onAwait = { error("must not park") }

        val result = useCase().execute(command(cursor = 0)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()

        result.inboxId shouldBe inbox.id
        result.afterStorageRefusalCount shouldBe 0
        result.refusals shouldBe recorded
        result.refusalReason shouldBe StorageRefusalReason.INBOX_LIMIT
        notifier.handles.size shouldBe 0
        waitSlots.peakHeld shouldBe 0
        metrics.completed shouldBe listOf(WaitOutcome.STORAGE_LIMIT_EXCEEDED)
        metrics.slotDelta.get() shouldBe 0
        observations.evaluations.get() shouldBe 1
    }

    @Test
    fun `the 409 body names the inbox scope's limit and current usage from the deciding snapshot`() {
        observations.figures = { TenantStorageFigures(inbox = StorageUsage(480, 30), workspace = StorageUsage(700, 100)) }
        refuse(StorageRefusalReason.INBOX_LIMIT)
        val result = useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.tenantScope shouldBe WaitForMessage.TenantScopeFigures(limitBytes = 500, currentBytes = 510)
        observations.storageReads.get() shouldBe 1
    }

    @Test
    fun `a WORKSPACE_LIMIT refusal names the workspace scope`() {
        observations.figures = { TenantStorageFigures(inbox = StorageUsage(10, 0), workspace = StorageUsage(950, 60)) }
        refuse(StorageRefusalReason.WORKSPACE_LIMIT)
        val result = useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.tenantScope shouldBe WaitForMessage.TenantScopeFigures(limitBytes = 1_000, currentBytes = 1_010)
    }

    @Test
    fun `a SERVICE_CAPACITY refusal is one bit - no scope figures, and the figures are not even read`() {
        observations.figures = { error("no byte figure may be read for SERVICE_CAPACITY") }
        refuse(StorageRefusalReason.SERVICE_CAPACITY)
        val result = useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.refusalReason shouldBe StorageRefusalReason.SERVICE_CAPACITY
        result.tenantScope shouldBe null
        observations.storageReads.get() shouldBe 0
    }

    // --- test 39 / 40 / §20: a match always wins ------------------------------------------

    @Test
    fun `a snapshot holding both a match and a newer refusal is MATCHED, and the refusal stays observable`() {
        messages.appendVisible(message())
        val recorded = refuse()

        val matched = useCase().execute(command(cursor = 0)).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        matched.refusals shouldBe recorded
        observations.evaluations.get() shouldBe 1 // decided by the initial check, no later read

        // The same unadvanced boundary, with a matcher the old message does not satisfy: the refusal was not swallowed.
        useCase()
            .execute(command(cursor = 0, matcher = MessageMatcher(subjectContains = "other")))
            .shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
            .refusals shouldBe recorded
    }

    @Test
    fun `match then refusal committed between check and subscribe - the recheck sees both and the match wins`() {
        hook =
            object : WaitSyncHook {
                override fun afterInitialCheck(inboxId: InboxId) {
                    messages.appendVisible(message())
                    refuse()
                }
            }
        notifier.onAwait = { error("must not park") }
        val matched = useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        matched.refusals.count shouldBe 1
        observations.evaluations.get() shouldBe 2 // check, then the recheck that decided
    }

    @Test
    fun `refusal then match committed between check and subscribe - the match still wins`() {
        hook =
            object : WaitSyncHook {
                override fun afterInitialCheck(inboxId: InboxId) {
                    refuse()
                    messages.appendVisible(message())
                }
            }
        notifier.onAwait = { error("must not park") }
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
    }

    @Test
    fun `only a refusal committed between check and subscribe - the recheck answers 409 without a slot`() {
        hook =
            object : WaitSyncHook {
                override fun afterSubscribe(inboxId: InboxId) {
                    refuse()
                }
            }
        notifier.onAwait = { error("must not park") }
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        waitSlots.peakHeld shouldBe 0
        notifier.handles.single().closed shouldBe true
    }

    // --- test 38 / §27: refusal while parked -----------------------------------------------

    @Test
    fun `a refusal arriving while parked wakes the waiter, answers 409 and releases the slot`() {
        maxConcurrentWaits = 1
        onFirstWakeThenExpire {
            refuse()
        }
        val result = useCase().execute(command(cursor = 0)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.refusals.count shouldBe 1
        result.afterStorageRefusalCount shouldBe 0
        waitSlots.peakHeld shouldBe 1
        waitSlots.held shouldBe 0
        metrics.slotDelta.get() shouldBe 0
        metrics.completed shouldBe listOf(WaitOutcome.STORAGE_LIMIT_EXCEEDED)
        notifier.handles.single().closed shouldBe true
    }

    // --- §23 precedence: 409 before the slot 429 -------------------------------------------

    @Test
    fun `an already-visible refusal is answered before the concurrent-wait slot is even considered`() {
        maxConcurrentWaits = 1
        waitSlots.acquire(workspaceId, 1, Duration.ofSeconds(60))!! // someone else holds the only slot
        refuse()
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        // And with no refusal the same situation is the slot refusal, as before.
        useCase()
            .execute(command(cursor = 1))
            .shouldBeInstanceOf<WaitForMessage.Result.ConcurrentWaitLimitExceeded>()
    }

    // --- test 41: cursor edges --------------------------------------------------------------

    @Test
    fun `a cursor equal to the count is not a 409 - the wait parks and TIMEOUT echoes the count`() {
        val recorded = refuse()
        expireOnFirstPark()
        val timeout = useCase().execute(command(cursor = 1)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.refusals shouldBe recorded
        notifier.handles.single().awaitCount shouldBe 1
    }

    @Test
    fun `a cursor below the count is a 409 with the caller's boundary`() {
        refuse()
        refuse()
        refuse()
        val result = useCase().execute(command(cursor = 2)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.afterStorageRefusalCount shouldBe 2
        result.refusals.count shouldBe 3
    }

    @Test
    fun `a cursor above the count is clamped ONCE to the initial count, so the next refusal is still a 409`() {
        repeat(5) { refuse() }
        onFirstWakeThenExpire {
            refuse() // count becomes 6 while parked
        }
        val result = useCase().execute(command(cursor = 100)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        // Re-clamping at the wake would have computed min(100, 6) = 6 and parked forever.
        result.afterStorageRefusalCount shouldBe 5
        result.refusals.count shouldBe 6
    }

    @Test
    fun `Long MAX_VALUE as a cursor behaves as the current count`() {
        repeat(2) { refuse() }
        onFirstWakeThenExpire {
            refuse()
        }
        val result = useCase().execute(command(cursor = Long.MAX_VALUE)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        result.afterStorageRefusalCount shouldBe 2
        result.refusals.count shouldBe 3
    }

    @Test
    fun `a negative cursor is an invalid request, decided before the inbox is even looked up`() {
        useCase()
            .execute(command(cursor = -1, inboxId = InboxId(UUID.randomUUID())))
            .shouldBeInstanceOf<WaitForMessage.Result.InvalidRequest>()
            .reason shouldBe "afterStorageRefusalCount must not be negative"
        metrics.completed shouldBe listOf(WaitOutcome.INVALID_REQUEST)
    }

    // --- §37 legacy guarantee: an omitted cursor never sees a 409 --------------------------

    @Test
    fun `an omitted cursor with refusals already recorded parks and ends TIMEOUT, carrying the count`() {
        val recorded = refuse()
        expireOnFirstPark()
        val timeout = useCase().execute(command(cursor = null)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.refusals shouldBe recorded
        timeout.arrivedButUnmatchedCount shouldBe 0
    }

    @Test
    fun `an omitted cursor is woken by a refusal mid-wait and still ends TIMEOUT or MATCHED, never 409`() {
        var wakes = 0
        notifier.onAwait = {
            wakes++
            if (wakes == 1) {
                refuse()
                WakeOutcome.WOKEN
            } else {
                clock.advanceSeconds(11)
                WakeOutcome.DEADLINE
            }
        }
        val timeout = useCase().execute(command(cursor = null)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.refusals.count shouldBe 1
        wakes shouldBe 2

        notifier.onAwait = {
            refuse()
            messages.appendVisible(message())
            WakeOutcome.WOKEN
        }
        useCase()
            .execute(command(cursor = null))
            .shouldBeInstanceOf<WaitForMessage.Result.Matched>()
            .refusals.count shouldBe 2
    }

    @Test
    fun `absence is not zero - the same situation is a 409 with cursor 0 and a TIMEOUT without one`() {
        refuse()
        expireOnFirstPark()
        useCase().execute(command(cursor = 0)).shouldBeInstanceOf<WaitForMessage.Result.StorageLimitExceeded>()
        useCase().execute(command(cursor = null)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
    }

    // --- test 42 / §21 / §22: the echo and the diagnostics come from the deciding snapshot ---

    @Test
    fun `a refusal committed after the decision is not echoed by MATCHED or by TIMEOUT`() {
        hook =
            object : WaitSyncHook {
                override fun afterDecision(inboxId: InboxId) {
                    refuse()
                }
            }
        messages.appendVisible(message())
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>().refusals shouldBe StorageRefusalSnapshot.NONE
        refusals.of(inbox.id).count shouldBe 1

        messages.messages.clear()
        expireOnFirstPark()
        val timeout = useCase().execute(command(cursor = 1)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.refusals.count shouldBe 1 // the one from before, not the one committed after the decision
        refusals.of(inbox.id).count shouldBe 2
    }

    @Test
    fun `TIMEOUT diagnostics and echo come from the final evaluation at the deadline, with no later read`() {
        var wakes = 0
        notifier.onAwait = {
            wakes++
            if (wakes == 1) {
                // Committed while parked: an unmatched message and a refusal.
                messages.appendVisible(message(subject = "unrelated", receivedAt = clock.now.plusSeconds(1)))
                refuse()
                clock.advanceSeconds(11)
                WakeOutcome.DEADLINE
            } else {
                error("the window has expired")
            }
        }
        val evaluationsBefore = observations.evaluations.get()
        // No cursor: the refusal is informational, so the call ends TIMEOUT and echoes it.
        val timeout =
            useCase()
                .execute(command(cursor = null, matcher = MessageMatcher(subjectContains = "expected")))
                .shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.arrivedButUnmatchedCount shouldBe 1
        timeout.refusals.count shouldBe 1
        // check, recheck, the park-loop evaluation, and the one at the deadline: nothing after it.
        observations.evaluations.get() - evaluationsBefore shouldBe 4
    }

    // --- §29: a refusal on another inbox ----------------------------------------------------

    @Test
    fun `a refusal on another inbox never ends this wait`() {
        notifier.onAwait = {
            refuse(inboxId = other.id) // even if the wake reached this waiter...
            clock.advanceSeconds(11)
            WakeOutcome.WOKEN
        }
        val timeout = useCase().execute(command(cursor = 0)).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.refusals shouldBe StorageRefusalSnapshot.NONE
        refusals.of(other.id).count shouldBe 1
    }

    // --- §47 metrics ---------------------------------------------------------------------

    @Test
    fun `every outcome is counted exactly once, including the new one`() {
        refuse()
        useCase().execute(command(cursor = 0))
        expireOnFirstPark()
        useCase().execute(command(cursor = null))
        metrics.completed shouldBe listOf(WaitOutcome.STORAGE_LIMIT_EXCEEDED, WaitOutcome.TIMEOUT)
        metrics.started.get() shouldBe metrics.completed.size
    }
}
