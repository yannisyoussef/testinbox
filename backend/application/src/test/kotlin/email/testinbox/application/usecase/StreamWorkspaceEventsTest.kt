package email.testinbox.application.usecase

import email.testinbox.application.FakeNotifier
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
import email.testinbox.domain.message.Message
import email.testinbox.domain.message.ParseStatus
import email.testinbox.domain.message.ParsedContent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
            inboxScanInterval = Duration.ofSeconds(2),
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
    fun `a stream subscribes to every inbox before its first read and holds one wait slot until closed`() {
        val stream = open()
        notifier.handles.single().inboxId shouldBe null
        slots.held shouldBe 1
        stream.close()
        slots.held shouldBe 0
        notifier.handles.single().closed shouldBe true
    }

    @Test
    fun `a workspace holding every slot is refused, like a wait (ADR-027 §3)`() {
        val useCase = useCase(maxConcurrentWaits = 1)
        val first = useCase.open(workspaceId, null).shouldBeInstanceOf<StreamWorkspaceEvents.Open.Opened>()
        useCase.open(workspaceId, null).shouldBeInstanceOf<StreamWorkspaceEvents.Open.ConcurrentWaitLimitExceeded>().limit shouldBe 1
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
    fun `a resumed cursor delivers the backlog on the first call without parking`() {
        val inbox = inbox()
        val seen = message(inbox, receivedAt = clock.now.minusSeconds(3))
        val missed1 = message(inbox, receivedAt = clock.now.minusSeconds(2))
        val missed2 = message(inbox, receivedAt = clock.now.minusSeconds(1))
        val stream = open(after = MessageCursor(seen.receivedAt, seen.id))
        received(stream.next(clock.now.plusSeconds(1))) shouldContainExactly listOf(missed1.id, missed2.id)
        notifier.handles.single().awaitCount shouldBe 0
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
