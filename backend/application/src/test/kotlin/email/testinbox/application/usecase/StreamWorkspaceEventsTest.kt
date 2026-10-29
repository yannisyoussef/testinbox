package email.testinbox.application.usecase

import email.testinbox.application.FakeNotifier
import email.testinbox.application.FakeRateLimiter
import email.testinbox.application.FakeWaitSlots
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.MutableClock
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.MessageCursor
import email.testinbox.application.port.WakeOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The workspace event stream on fakes: ordering, resume, scoping, the slot it
 * holds and the lifecycle diff. The real LISTEN path and SQL servability are
 * proven in the api and persistence suites.
 */
class StreamWorkspaceEventsTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())
    private val otherWorkspace = WorkspaceId(UUID.randomUUID())
    private val inboxes = InMemoryInboxRepository()
    private val messages = InMemoryMessageRepository()
    private val notifier = FakeNotifier()
    private val slots = FakeWaitSlots()
    private val rateLimiter = FakeRateLimiter()
    private val metrics = RecordingWaitMetrics()
    private val clock = MutableClock(Instant.parse("2026-10-20T10:00:00Z"))
    private val config = TestInboxConfig(mailDomain = "testinbox.local", waitWindowCap = Duration.ofSeconds(60))

    private fun useCase(maxConcurrentWaits: Long = 5) =
        StreamWorkspaceEvents(
            inboxes,
            messages,
            notifier,
            slots,
            maxConcurrentWaits,
            clock,
            config,
            rateLimiter,
            metrics,
            inboxScanInterval = Duration.ofSeconds(2),
            visibilityLag = Duration.ofSeconds(60),
        )

    private fun inbox(
        workspace: WorkspaceId = workspaceId,
        state: InboxState = InboxState.ACTIVE,
        createdAt: Instant = clock.now,
    ): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = workspace,
            projectId = projectId,
            address = "${UUID.randomUUID()}@testinbox.local",
            addressMode = AddressMode.GENERATED,
            state = state,
            createdAt = createdAt,
            expiresAt = createdAt.plusSeconds(600),
            graceUntil = null,
        ).also { inboxes.inboxes[it.id] = it }

    private fun message(
        inbox: Inbox,
        receivedAt: Instant = clock.now,
        subject: String = "hello",
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            workspaceId = inbox.workspaceId,
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
        ).also { messages.appendVisible(it) }

    private fun open(after: MessageCursor? = null): StreamWorkspaceEvents.Stream =
        useCase().open(workspaceId, after).shouldBeInstanceOf<StreamWorkspaceEvents.Open.Opened>().stream

    private fun received(events: List<WorkspaceEvent>) = events.filterIsInstance<WorkspaceEvent.MessageReceived>().map { it.message.id }

    @Test
    fun `a stream subscribes to its own workspace before its first read and holds one metered wait slot until closed`() {
        val stream = open()
        notifier.handles.single().inboxId shouldBe null
        notifier.handles.single().workspaceId shouldBe workspaceId
        slots.held shouldBe 1
        metrics.slotDelta.get() shouldBe 1
        stream.close()
        slots.held shouldBe 0
        metrics.slotDelta.get() shouldBe 0
        notifier.handles.single().closed shouldBe true
    }

    @Test
    fun `a subscription that fails releases the slot it had just taken`() {
        notifier.subscribeFailure = IllegalStateException("LISTEN down")
        shouldThrow<IllegalStateException> { useCase().open(workspaceId, null) }
        slots.held shouldBe 0
        metrics.slotDelta.get() shouldBe 0
    }

    @Test
    fun `a workspace holding every slot is refused, like a wait (ADR-027 §7)`() {
        val useCase = useCase(maxConcurrentWaits = 1)
        val first = useCase.open(workspaceId, null).shouldBeInstanceOf<StreamWorkspaceEvents.Open.Opened>()
        useCase.open(workspaceId, null).shouldBeInstanceOf<StreamWorkspaceEvents.Open.ConcurrentWaitLimitExceeded>().limit shouldBe 1
        metrics.slotRejections.get() shouldBe 1
        first.stream.close()
        useCase
            .open(workspaceId, null)
            .shouldBeInstanceOf<StreamWorkspaceEvents.Open.Opened>()
            .stream
            .close()
    }

    @Test
    fun `messages visible before the stream opened are not replayed, later ones arrive in receipt order, each once`() {
        val inbox = inbox()
        message(inbox, receivedAt = clock.now.minusSeconds(5))
        val stream = open()
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()

        clock.advanceSeconds(1)
        val a = message(inbox, receivedAt = clock.now)
        val b = message(inbox, receivedAt = clock.now.plusMillis(1))
        notifier.onAwait = { WakeOutcome.WOKEN }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(a.id, b.id)
        // Nothing new: the next wake yields nothing, and nothing is repeated.
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()
        stream.position shouldBe MessageCursor(b.receivedAt, b.id)
    }

    @Test
    fun `a resumed position delivers the backlog on the first call without parking, re-reading the horizon behind it`() {
        val inbox = inbox()
        val outside = message(inbox, receivedAt = clock.now.minusSeconds(70))
        val seen = message(inbox, receivedAt = clock.now.minusSeconds(3))
        val missed1 = message(inbox, receivedAt = clock.now.minusSeconds(2))
        val missed2 = message(inbox, receivedAt = clock.now.minusSeconds(1))
        val stream = open(after = MessageCursor(seen.receivedAt, seen.id))
        // At-least-once across windows: `seen` sits inside the horizon behind the
        // position and is repeated; `outside` is beyond it and is not.
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(seen.id, missed1.id, missed2.id)
        notifier.handles.single().awaitCount shouldBe 0
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()
        outside.id shouldNotBe seen.id
    }

    @Test
    fun `a message that commits with an older receipt time than one already delivered is still delivered, once`() {
        // receivedAt is stamped at DATA, before the raw upload and the commit
        // (ADR-005): a slow upload makes a message visible "in the past".
        val inbox = inbox()
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()
        clock.advanceSeconds(5)
        val quick = message(inbox, receivedAt = clock.now)
        notifier.onAwait = { WakeOutcome.WOKEN }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(quick.id)
        val slow = message(inbox, receivedAt = clock.now.minusSeconds(4))
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(slow.id)
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()
        // The resume position is the newest receipt, not the last delivery.
        stream.position shouldBe MessageCursor(quick.receivedAt, quick.id)
    }

    @Test
    fun `a message received beyond the horizon before the newest position is outside the guarantee`() {
        val inbox = inbox()
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()
        clock.advanceSeconds(1)
        val newest = message(inbox, receivedAt = clock.now)
        notifier.onAwait = { WakeOutcome.WOKEN }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(newest.id)
        message(inbox, receivedAt = clock.now.minusSeconds(61))
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()
    }

    @Test
    fun `a backlog larger than one page streams out a page per call, in order, without parking in between`() {
        val inbox = inbox()
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()
        clock.advanceSeconds(1)
        val backlog = (1..450).map { message(inbox, receivedAt = clock.now.plusMillis(it.toLong())) }
        notifier.onAwait = { WakeOutcome.WOKEN }
        val handle = notifier.handles.single()
        val awaitsBefore = handle.awaitCount
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly backlog.take(200).map { it.id }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly backlog.drop(200).take(200).map { it.id }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly backlog.drop(400).map { it.id }
        // One park (the wake that started it); the two continuation calls parked nowhere,
        // each spending one READ token.
        handle.awaitCount shouldBe awaitsBefore + 1
        rateLimiter.calls.count { it.first == RateCategory.READ } shouldBe 2
        received(stream.next(clock.now.plusSeconds(1))).shouldBeEmpty()
        handle.awaitCount shouldBe awaitsBefore + 2
    }

    @Test
    fun `a backlog walk with no READ budget left parks between pages instead of looping`() {
        val inbox = inbox()
        (1..450).map { message(inbox, receivedAt = clock.now.minusSeconds(500L - it)) }
        rateLimiter.exhausted += RateCategory.READ
        val stream = open(after = MessageCursor(clock.now.minusSeconds(1_000), MessageId(UUID(0L, 0L))))
        val handle = notifier.handles.single()
        received(stream.next(clock.now.plusSeconds(1))).size shouldBe 200
        handle.awaitCount shouldBe 0
        // The next page waits for a wake or the scan tick: the walk is paced, not abandoned.
        notifier.onAwait = { WakeOutcome.DEADLINE }
        received(stream.next(clock.now.plusSeconds(1))).size shouldBe 200
        handle.awaitCount shouldBe 1
        received(stream.next(clock.now.plusSeconds(1))).size shouldBe 50
        handle.awaitCount shouldBe 2
    }

    @Test
    fun `a resume from the epoch never holds more than a page of rows per call`() {
        // A key holder can pass any position; the cost of an ancient one is
        // one page per call, never the workspace's whole history in memory.
        val inbox = inbox()
        val history = (1..1_000).map { message(inbox, receivedAt = clock.now.minusSeconds(100_000L - it)) }
        val stream = open(after = MessageCursor(Instant.EPOCH, MessageId(UUID(0L, 0L))))
        messages.maxPageServed shouldBe 0
        val pages = generateSequence { received(stream.next(clock.now.plusSeconds(1))).takeIf { it.isNotEmpty() } }.toList()
        pages.map { it.size } shouldContainExactly listOf(200, 200, 200, 200, 200)
        pages.flatten() shouldContainExactly history.map { it.id }
        messages.maxPageServed shouldBe 200
        stream.position shouldBe MessageCursor(history.last().receivedAt, history.last().id)
    }

    @Test
    fun `the lifecycle diff walks every row, so a workspace past one page reports no spurious gone or created`() {
        repeat(1_201) { inbox(createdAt = clock.now.minusSeconds(it.toLong())) }
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()
        clock.advanceSeconds(2)
        // A new inbox pushes the oldest out of any bounded window; nothing is reported but it.
        val created = inbox(createdAt = clock.now)
        stream.next(clock.now).filterIsInstance<WorkspaceEvent.InboxChanged>().map { it.inbox.id to it.change } shouldContainExactly
            listOf(created.id to WorkspaceEvent.InboxChange.CREATED)
    }

    @Test
    fun `another workspace's messages never appear, whatever woke the stream`() {
        val mine = inbox()
        val theirs = inbox(workspace = otherWorkspace)
        val stream = open()
        clock.advanceSeconds(1)
        message(theirs, receivedAt = clock.now)
        val own = message(mine, receivedAt = clock.now.plusMillis(1))
        notifier.onAwait = { WakeOutcome.WOKEN }
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(own.id)
    }

    @Test
    fun `inbox lifecycle is reported by the periodic diff - created, expiring, expired, deleted, gone`() {
        val existing = inbox()
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()

        // Before the scan interval elapses, a deadline (no wake) reads no lifecycle.
        val created = inbox(createdAt = clock.now)
        notifier.onAwait = { WakeOutcome.DEADLINE }
        stream.next(clock.now.plusSeconds(1)).filterIsInstance<WorkspaceEvent.InboxChanged>().shouldBeEmpty()

        clock.advanceSeconds(2)
        var changes = stream.next(clock.now).filterIsInstance<WorkspaceEvent.InboxChanged>()
        changes.map { it.inbox.id to it.change } shouldContainExactly listOf(created.id to WorkspaceEvent.InboxChange.CREATED)

        inboxes.inboxes[existing.id] = existing.copy(state = InboxState.EXPIRING)
        clock.advanceSeconds(2)
        changes = stream.next(clock.now).filterIsInstance<WorkspaceEvent.InboxChanged>()
        changes.map { it.inbox.id to it.change } shouldContainExactly listOf(existing.id to WorkspaceEvent.InboxChange.EXPIRING)

        inboxes.inboxes[existing.id] = existing.copy(state = InboxState.EXPIRED)
        inboxes.markDeleted(workspaceId, created.id, clock.now)
        clock.advanceSeconds(2)
        changes = stream.next(clock.now).filterIsInstance<WorkspaceEvent.InboxChanged>()
        changes.map { it.inbox.id to it.change }.toSet() shouldBe
            setOf(existing.id to WorkspaceEvent.InboxChange.EXPIRED, created.id to WorkspaceEvent.InboxChange.DELETED)

        inboxes.inboxes.remove(existing.id)
        clock.advanceSeconds(2)
        changes = stream.next(clock.now).filterIsInstance<WorkspaceEvent.InboxChanged>()
        changes.map { it.inbox.id to it.change } shouldContainExactly listOf(existing.id to WorkspaceEvent.InboxChange.GONE)
    }

    @Test
    fun `a wake re-reads the lifecycle at once, since EXPIRED and DELETED notify (issue 87)`() {
        val inbox = inbox()
        val stream = open()
        stream.next(clock.now.plusSeconds(1)).shouldBeEmpty()
        inboxes.markDeleted(workspaceId, inbox.id, clock.now)
        notifier.onAwait = { WakeOutcome.WOKEN }
        stream.next(clock.now.plusSeconds(1)).filterIsInstance<WorkspaceEvent.InboxChanged>().map { it.change } shouldContainExactly
            listOf(WorkspaceEvent.InboxChange.DELETED)
    }

    @Test
    fun `the window ends at wait-window-cap from the open`() {
        val stream = open()
        stream.deadline shouldBe clock.now.plus(Duration.ofSeconds(60))
    }
}
