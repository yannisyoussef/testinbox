package email.testinbox.application.usecase

import email.testinbox.application.FakeNotifier
import email.testinbox.application.FakeWaitSlots
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryRefusals
import email.testinbox.application.InMemoryWaitObservations
import email.testinbox.application.MutableClock
import email.testinbox.application.TestInboxConfig
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
import email.testinbox.domain.storage.StorageCapacityPolicy
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class WaitForMessageTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private lateinit var inboxes: InMemoryInboxRepository
    private lateinit var messages: InMemoryMessageRepository
    private lateinit var notifier: FakeNotifier
    private lateinit var clock: MutableClock
    private lateinit var inbox: Inbox
    private var hook: WaitSyncHook = WaitSyncHook.NOOP
    private var waitSlots = FakeWaitSlots()
    private var maxConcurrentWaits: Long = 10

    private val config = TestInboxConfig(mailDomain = "testinbox.local", waitWindowCap = Duration.ofSeconds(60))

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        messages = InMemoryMessageRepository()
        notifier = FakeNotifier()
        clock = MutableClock(Instant.parse("2026-08-29T12:00:00Z"))
        inbox =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = ProjectId(UUID.randomUUID()),
                address = "test@testinbox.local",
                addressMode = AddressMode.GENERATED,
                state = InboxState.ACTIVE,
                createdAt = clock.now,
                expiresAt = clock.now.plusSeconds(600),
            )
        inboxes.insert(inbox)
    }

    private val metrics = RecordingWaitMetrics()
    private val refusals = InMemoryRefusals()

    private fun useCase(): WaitForMessage =
        WaitForMessage(
            inboxes,
            InMemoryWaitObservations(messages, refusals, inboxes = inboxes),
            notifier,
            waitSlots,
            maxConcurrentWaits,
            clock,
            config,
            StorageCapacityPolicy.ADR_035_REFERENCE,
            hook,
            metrics,
        )

    private fun command(
        matcher: MessageMatcher = MessageMatcher(),
        timeoutSeconds: Long = 10,
    ) = WaitForMessage.Command(workspaceId, inbox.id, matcher, timeoutSeconds)

    private fun visibleMessage(
        subject: String = "hello",
        parseStatus: ParseStatus = ParseStatus.OK,
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
            parseStatus = parseStatus,
            parseError = if (parseStatus == ParseStatus.FAILED) "bad" else null,
            parsed =
                if (parseStatus == ParseStatus.OK) {
                    ParsedContent("sut@example.com", null, null, subject, "b", null, emptyList(), emptyList())
                } else {
                    null
                },
            attachments = emptyList(),
        )

    @Test
    fun `message arriving before the wait starts is returned immediately without subscribing`() {
        messages.appendVisible(visibleMessage())
        val result = useCase().execute(command())
        result.shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        notifier.handles.size shouldBe 0
    }

    @Test
    fun `message arriving between initial check and subscription is caught by the recheck`() {
        hook =
            object : WaitSyncHook {
                override fun afterInitialCheck(inboxId: InboxId) {
                    messages.appendVisible(visibleMessage())
                }
            }
        // The notifier never signals: only the post-subscribe recheck can find it.
        notifier.onAwait = { error("waiter must not park — recheck should have matched") }
        val result = useCase().execute(command())
        result.shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        notifier.handles.single().closed shouldBe true
    }

    @Test
    fun `message arriving after subscription wakes the parked waiter`() {
        notifier.onAwait = { handle ->
            messages.appendVisible(visibleMessage())
            WakeOutcome.WOKEN
        }
        val result = useCase().execute(command())
        result.shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        notifier.handles.single().awaitCount shouldBe 1
        notifier.handles.single().closed shouldBe true
    }

    @Test
    fun `earliest matching message wins and waits are non-consuming`() {
        val first = visibleMessage(receivedAt = clock.now.minusSeconds(5))
        messages.appendVisible(first)
        messages.appendVisible(visibleMessage(receivedAt = clock.now.minusSeconds(1)))
        val result = useCase().execute(command())
        result.shouldBeInstanceOf<WaitForMessage.Result.Matched>().message.id shouldBe first.id
        // Non-consuming: a second wait sees the same message.
        useCase()
            .execute(command())
            .shouldBeInstanceOf<WaitForMessage.Result.Matched>()
            .message.id shouldBe first.id
    }

    @Test
    fun `timeout reports unmatched and parse-failed diagnostics (ADR-020)`() {
        notifier.onAwait = { _ ->
            if (messages.messages.isEmpty()) {
                messages.appendVisible(visibleMessage(subject = "wrong"))
                messages.appendVisible(visibleMessage(parseStatus = ParseStatus.FAILED))
                WakeOutcome.WOKEN
            } else {
                clock.advanceSeconds(11)
                WakeOutcome.DEADLINE
            }
        }
        val result = useCase().execute(command(MessageMatcher(subjectContains = "expected")))
        val timeout = result.shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        timeout.arrivedButUnmatchedCount shouldBe 1
        timeout.parseFailedCount shouldBe 1
        timeout.elapsedMs shouldBe 11_000
    }

    @Test
    fun `parse-failed messages never satisfy a matcher`() {
        messages.appendVisible(visibleMessage(parseStatus = ParseStatus.FAILED))
        notifier.onAwait = {
            clock.advanceSeconds(11)
            WakeOutcome.DEADLINE
        }
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
    }

    @Test
    fun `requested timeout is capped by the server wait window`() {
        notifier.onAwait = { _ ->
            clock.advanceSeconds(61)
            WakeOutcome.DEADLINE
        }
        val result = useCase().execute(command(timeoutSeconds = 3600))
        // Window capped at 60s: after 61s the wait must have expired.
        result.shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        notifier.handles.single().awaitCount shouldBe 1
    }

    @Test
    fun `non-active inbox yields Gone and unknown inbox NotFound`() {
        inboxes.inboxes[inbox.id] = inbox.copy(state = InboxState.EXPIRING, graceUntil = clock.now.plusSeconds(30))
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.InboxGone>()
        useCase()
            .execute(command().copy(inboxId = InboxId(UUID.randomUUID())))
            .shouldBeInstanceOf<WaitForMessage.Result.InboxNotFound>()
    }

    @Test
    fun `cross-workspace wait is NotFound, not Gone (no existence leakage)`() {
        useCase()
            .execute(command().copy(workspaceId = WorkspaceId(UUID.randomUUID())))
            .shouldBeInstanceOf<WaitForMessage.Result.InboxNotFound>()
    }

    @Test
    fun `an immediately satisfiable wait claims no concurrency slot`() {
        maxConcurrentWaits = 1
        messages.appendVisible(visibleMessage())
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        // Never parked, so never charged: the slot is claimed only just before
        // parking (ADR-027 §7).
        waitSlots.peakHeld shouldBe 0
        waitSlots.held shouldBe 0
    }

    @Test
    fun `a waiter that must park is refused when the workspace holds every slot`() {
        maxConcurrentWaits = 1
        // Someone else already holds the workspace's only slot.
        waitSlots.acquire(workspaceId, 1, java.time.Duration.ofSeconds(60))!!
        notifier.onAwait = { error("must not park: the slot was unavailable") }
        val result = useCase().execute(command())
        result.shouldBeInstanceOf<WaitForMessage.Result.ConcurrentWaitLimitExceeded>().limit shouldBe 1L
    }

    @Test
    fun `a slot is released even when the wait fails part-way`() {
        maxConcurrentWaits = 1
        notifier.onAwait = { error("boom") }
        runCatching { useCase().execute(command()) }.isFailure shouldBe true
        // Released on the exception path too, or one crash would permanently
        // shrink the tenant's allowance.
        waitSlots.held shouldBe 0
        // And the next waiter can still claim it.
        waitSlots.acquire(workspaceId, 1, java.time.Duration.ofSeconds(60)).shouldNotBeNull()
    }

    @Test
    fun `a slot is released after a normal timeout`() {
        maxConcurrentWaits = 1
        notifier.onAwait = { _ ->
            clock.advanceSeconds(11)
            WakeOutcome.DEADLINE
        }
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        waitSlots.held shouldBe 0
        waitSlots.peakHeld shouldBe 1
    }

    // --- issue #87: a parked wait ends 410 when its inbox stops serving --------------------------

    private fun expire(id: InboxId) {
        inboxes.transitionToExpiring(id, clock.now) shouldBe true
        inboxes.transitionToExpired(id) shouldBe true
    }

    @Test
    fun `1 - a parked wait whose inbox becomes EXPIRED ends Gone at the wake, never a match`() {
        notifier.onAwait = { _ ->
            expire(inbox.id)
            WakeOutcome.WOKEN
        }
        val result = useCase().execute(command())
        result shouldBe WaitForMessage.Result.InboxGone
        notifier.handles.single().awaitCount shouldBe 1 // decided on the first re-evaluation, no further park
        notifier.handles.single().closed shouldBe true
        waitSlots.held shouldBe 0
    }

    @Test
    fun `2 - a parked wait whose inbox is DELETED ends Gone at the wake`() {
        notifier.onAwait = { _ ->
            inboxes.markDeleted(workspaceId, inbox.id, clock.now)
            WakeOutcome.WOKEN
        }
        useCase().execute(command()) shouldBe WaitForMessage.Result.InboxGone
        notifier.handles.single().closed shouldBe true
    }

    @Test
    fun `3 - a message matching before expiry is returned, and the same inbox expired later is Gone`() {
        messages.appendVisible(visibleMessage())
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        expire(inbox.id)
        useCase().execute(command()) shouldBe WaitForMessage.Result.InboxGone
    }

    @Test
    fun `4 - a message committed before expiry but evaluated after it is not returned - the snapshot's state decides`() {
        notifier.onAwait = { _ ->
            messages.appendVisible(visibleMessage()) // arrived while parked...
            expire(inbox.id) // ...and the inbox expired before the waiter looked
            WakeOutcome.WOKEN
        }
        useCase().execute(command()) shouldBe WaitForMessage.Result.InboxGone
    }

    @Test
    fun `5 - EXPIRING keeps its grace semantics - a wait started while ACTIVE still matches during the grace period`() {
        notifier.onAwait = { _ ->
            inboxes.transitionToExpiring(inbox.id, clock.now.plusSeconds(30)) shouldBe true
            messages.appendVisible(visibleMessage())
            WakeOutcome.WOKEN
        }
        useCase().execute(command()).shouldBeInstanceOf<WaitForMessage.Result.Matched>()
        // A wait STARTED on an EXPIRING inbox is Gone, as before.
        useCase().execute(command()) shouldBe WaitForMessage.Result.InboxGone
    }

    @Test
    fun `6 - every concurrent waiter on the inbox ends Gone when it expires`() {
        val useCase = useCase()
        val parked = java.util.concurrent.CountDownLatch(2)
        val expired = java.util.concurrent.CountDownLatch(1)
        notifier.onAwait = { _ ->
            parked.countDown()
            expired.await(5, java.util.concurrent.TimeUnit.SECONDS)
            WakeOutcome.WOKEN
        }
        val results = java.util.concurrent.CopyOnWriteArrayList<WaitForMessage.Result>()
        val threads = (1..2).map { Thread { results += useCase.execute(command()) }.apply { start() } }
        parked.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        synchronized(messages) { expire(inbox.id) }
        expired.countDown()
        threads.forEach { it.join(5000) }
        results.size shouldBe 2
        results.forEach { it shouldBe WaitForMessage.Result.InboxGone }
    }

    @Test
    fun `7 - the degraded re-query sees the transition too - a wake with no notification still ends Gone`() {
        // A degraded tick or a reconnect epoch bump wakes the waiter without a payload (ADR-020);
        // the re-evaluation reads the state like any other and decides.
        var wakes = 0
        notifier.onAwait = { _ ->
            wakes++
            if (wakes == 2) expire(inbox.id)
            WakeOutcome.WOKEN
        }
        useCase().execute(command()) shouldBe WaitForMessage.Result.InboxGone
        wakes shouldBe 2
    }

    @Test
    fun `8 - a wait on an inbox that stays ACTIVE still times out exactly as before`() {
        notifier.onAwait = { _ ->
            clock.advanceSeconds(11)
            WakeOutcome.DEADLINE
        }
        val result = useCase().execute(command(timeoutSeconds = 10))
        result.shouldBeInstanceOf<WaitForMessage.Result.Timeout>()
        inboxes.inboxes[inbox.id]!!.state shouldBe InboxState.ACTIVE
    }

    @Test
    fun `two simultaneous waiters are independent and satisfied by the same message`() {
        val useCase = useCase()
        notifier.onAwait = { _ ->
            if (messages.messages.isEmpty()) messages.appendVisible(visibleMessage())
            WakeOutcome.WOKEN
        }
        val results = mutableListOf<WaitForMessage.Result>()
        val threads =
            (1..2).map {
                Thread {
                    val r = useCase.execute(command())
                    synchronized(results) { results += r }
                }.apply { start() }
            }
        threads.forEach { it.join(5000) }
        results.size shouldBe 2
        results.forEach { it.shouldBeInstanceOf<WaitForMessage.Result.Matched>() }
        val ids = results.map { (it as WaitForMessage.Result.Matched).message.id }.toSet()
        ids.size shouldBe 1
    }

    @Test
    fun `every wait is counted exactly once, whatever its outcome`() {
        // The started/completed pair is a try/finally contract: if it ever
        // breaks, `testinbox_wait_requests_active` drifts upward and a handle
        // leak becomes indistinguishable from load — which is the one thing
        // that gauge exists to tell apart.
        // An invalid request, then a match.
        useCase().execute(command(timeoutSeconds = 0))
        messages.appendVisible(visibleMessage())
        useCase().execute(command())

        metrics.started.get() shouldBe 2
        metrics.completed shouldBe listOf(WaitOutcome.INVALID_REQUEST, WaitOutcome.MATCHED)
        // Started and completed must stay balanced, or the gauge leaks.
        metrics.started.get() shouldBe metrics.completed.size
    }
}
