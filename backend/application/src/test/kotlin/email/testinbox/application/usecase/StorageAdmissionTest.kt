package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.domain.AttachmentId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The T1 decision (ADR-035 §4) without a database: envelope order, running
 * totals, refusal precedence, enforcement OFF, ownership, the empty event and
 * the database clock. `StorageAdmission*Test` in the persistence suite proves
 * the same against PostgreSQL.
 */
class StorageAdmissionTest {
    private val t0: Instant = Instant.parse("2026-09-29T12:00:00.123456Z")

    /** Records every scope it was asked for, and serves a fixed snapshot. */
    private inner class FakeStore(
        private val global: StorageUsage = StorageUsage.ZERO,
        private val workspaces: Map<WorkspaceId, StorageUsage> = emptyMap(),
        private val inboxes: Map<InboxId, InboxStorageUsage> = emptyMap(),
    ) : StorageAdmissionStore {
        val scopes = mutableListOf<StorageAdmissionScope>()
        val inserted = mutableListOf<StorageAdmissionPlan<*>>()

        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R {
            scopes += scope
            val snapshot =
                StorageUsageSnapshot(
                    t0 = t0,
                    global = global,
                    workspaces = scope.workspaceIds.associateWith { workspaces[it] ?: StorageUsage.ZERO },
                    inboxes = scope.inboxIds.associateWith { inboxes[it] ?: error("the test must declare inbox $it") },
                )
            return decide(snapshot).also { inserted += it }.outcome
        }
    }

    private val ws1 = WorkspaceId(UUID.randomUUID())
    private val ws2 = WorkspaceId(UUID.randomUUID())
    private val a1 = InboxId(UUID.randomUUID())
    private val a2 = InboxId(UUID.randomUUID())
    private val b1 = InboxId(UUID.randomUUID())
    private val b2 = InboxId(UUID.randomUUID())

    private fun owned(
        owner: WorkspaceId,
        used: Long = 0,
    ) = InboxStorageUsage(owner, StorageUsage(used, 0))

    private fun candidate(
        workspace: WorkspaceId,
        inbox: InboxId,
        attachments: Int = 0,
    ): StorageAdmissionCandidate {
        val message = MessageId(UUID.randomUUID())
        return StorageAdmissionCandidate(
            messageId = message,
            workspaceId = workspace,
            inboxId = inbox,
            objectKeys =
                listOf(ObjectKeys.raw(workspace, inbox, message)) +
                    List(attachments) { ObjectKeys.attachment(workspace, inbox, message, AttachmentId(UUID.randomUUID())) },
        )
    }

    private fun request(
        bytes: Long,
        vararg candidates: StorageAdmissionCandidate,
    ) = StorageAdmissionRequest(bytes, candidates.toList(), "node-a", UUID.randomUUID())

    /** Inbox 100, workspace 200, global cap 1 000 (G 1 100, H 100). */
    private val policy = StorageCapacityPolicy(200, InboxShare.of("0.5"), 1_100, 100)

    private fun admission(
        store: StorageAdmissionStore,
        enforcement: StorageEnforcement = StorageEnforcement.ON,
    ) = StorageAdmission(store, policy, enforcement)

    private fun StorageAdmissionResult.shape() =
        decisions.map {
            when (it) {
                is StorageAdmissionDecision.Admitted -> "admitted"
                is StorageAdmissionDecision.Refused -> it.reason.name
            }
        }

    @Test
    fun `an event with no candidate never reaches the store`() {
        val store = FakeStore()

        admission(store).admit(request(10)) shouldBe StorageAdmissionResult.NOTHING_TO_ADMIT

        store.scopes.shouldBeEmpty()
    }

    @Test
    fun `A1 over its workspace limit is refused while B1 and B2 are admitted`() {
        // ADR-035 §4: one tenant at capacity never fails another tenant's recipient.
        val store =
            FakeStore(
                workspaces = mapOf(ws1 to StorageUsage(190, 0)),
                inboxes = mapOf(a1 to owned(ws1), b1 to owned(ws2), b2 to owned(ws2)),
            )

        val result = admission(store).admit(request(20, candidate(ws1, a1), candidate(ws2, b1), candidate(ws2, b2)))

        result.shape() shouldBe listOf("WORKSPACE_LIMIT", "admitted", "admitted")
        store.inserted
            .single()
            .reservations
            .map { it.inboxId } shouldBe listOf(b1, b2)
    }

    @Test
    fun `copies admitted earlier in the event count against the next one`() {
        // Inbox 100 fits two copies of 40; the third would make 120.
        val store = FakeStore(inboxes = mapOf(a1 to owned(ws1)))
        val sameInbox = List(3) { candidate(ws1, a1) }

        admission(store).admit(request(40, *sameInbox.toTypedArray())).shape() shouldBe
            listOf("admitted", "admitted", "INBOX_LIMIT")
    }

    @Test
    fun `running totals span inboxes, because siblings share their workspace`() {
        // Workspace 200: three inboxes at 80 each admit two copies; the third is WORKSPACE_LIMIT.
        val a3 = InboxId(UUID.randomUUID())
        val store = FakeStore(inboxes = mapOf(a1 to owned(ws1), a2 to owned(ws1), a3 to owned(ws1)))

        admission(store).admit(request(80, candidate(ws1, a1), candidate(ws1, a2), candidate(ws1, a3))).shape() shouldBe
            listOf("admitted", "admitted", "WORKSPACE_LIMIT")
    }

    @Test
    fun `global exhaustion mid-event admits exactly the envelope-order prefix`() {
        // Cap 1 000, 900 used: exactly two copies of 50 fit, then every later
        // copy is SERVICE_CAPACITY, whoever it belongs to.
        val store =
            FakeStore(
                global = StorageUsage(900, 0),
                inboxes = mapOf(a1 to owned(ws1), a2 to owned(ws1), b1 to owned(ws2), b2 to owned(ws2)),
            )

        admission(store)
            .admit(request(50, candidate(ws2, b1), candidate(ws1, a1), candidate(ws2, b2), candidate(ws1, a2)))
            .shape() shouldBe listOf("admitted", "admitted", "SERVICE_CAPACITY", "SERVICE_CAPACITY")
    }

    @Test
    fun `a copy that fails narrower ceilings is refused for the narrowest, and does not consume global room`() {
        val store =
            FakeStore(
                global = StorageUsage(990, 0),
                workspaces = mapOf(ws1 to StorageUsage(195, 0)),
                inboxes = mapOf(a1 to owned(ws1, used = 99), b1 to owned(ws2)),
            )

        // a1 fails all three: INBOX_LIMIT. b1 then fits globally only because
        // nothing was admitted before it.
        admission(store).admit(request(10, candidate(ws1, a1), candidate(ws2, b1))).shape() shouldBe
            listOf("INBOX_LIMIT", "admitted")
    }

    @Test
    fun `enforcement OFF admits over every ceiling and records what it would have refused`() {
        val store =
            FakeStore(
                global = StorageUsage(5_000, 0),
                workspaces = mapOf(ws1 to StorageUsage(5_000, 0)),
                inboxes = mapOf(a1 to owned(ws1, used = 5_000), b1 to owned(ws2)),
            )

        val result = admission(store, StorageEnforcement.OFF).admit(request(10, candidate(ws1, a1), candidate(ws2, b1)))

        result.refused.shouldBeEmpty()
        result.admitted.map { it.unenforcedLimit } shouldBe
            listOf(StorageRefusalReason.INBOX_LIMIT, StorageRefusalReason.SERVICE_CAPACITY)
        store.inserted
            .single()
            .reservations.size shouldBe 2
    }

    @Test
    fun `reservations take t0 and t0 plus E from the database snapshot, and nothing else`() {
        val store = FakeStore(inboxes = mapOf(a1 to owned(ws1)))
        val c = candidate(ws1, a1, attachments = 2)

        val reservation =
            admission(store)
                .admit(request(30, c))
                .admitted
                .single()
                .reservation

        reservation.createdAt shouldBe t0
        reservation.writeDeadlineAt shouldBe t0.plus(Duration.ofSeconds(120))
        reservation.objectKeys shouldBe c.objectKeys
        reservation.bytes shouldBe 30
    }

    @Test
    fun `an inbox that does not exist, or belongs to another workspace, fails the whole T1 closed`() {
        val missing = FakeStore(inboxes = mapOf(a1 to InboxStorageUsage(null, StorageUsage.ZERO)))
        shouldThrow<StorageAdmissionInputException> { admission(missing).admit(request(10, candidate(ws1, a1))) }
        missing.inserted.shouldBeEmpty()

        val foreign = FakeStore(inboxes = mapOf(a1 to owned(ws2)))
        shouldThrow<StorageAdmissionInputException> { admission(foreign).admit(request(10, candidate(ws1, a1))) }
        foreign.inserted.shouldBeEmpty()
    }

    @Test
    fun `overflowing usage fails closed instead of wrapping into free capacity`() {
        val store = FakeStore(global = StorageUsage(Long.MAX_VALUE - 5, 0), inboxes = mapOf(a1 to owned(ws1)))

        shouldThrow<StorageAccountingOverflowException> { admission(store).admit(request(10, candidate(ws1, a1))) }
        store.inserted.shouldBeEmpty()
    }

    @Test
    fun `a candidate must own exactly its own keys`() {
        val message = MessageId(UUID.randomUUID())
        val otherMessage = MessageId(UUID.randomUUID())

        fun make(keys: List<String>) = StorageAdmissionCandidate(message, ws1, a1, keys)

        shouldThrow<IllegalArgumentException> { make(emptyList()) }
        shouldThrow<IllegalArgumentException> { make(listOf(ObjectKeys.raw(ws1, a1, otherMessage))) }
        shouldThrow<IllegalArgumentException> { make(listOf(ObjectKeys.raw(ws2, a1, message))) }
        shouldThrow<IllegalArgumentException> { make(listOf("$ws1/$a1/$message/../../x/raw.eml")) }
        shouldThrow<IllegalArgumentException> {
            make(listOf(ObjectKeys.raw(ws1, a1, message), "$ws1/$a1/$message/attachments/not-a-uuid"))
        }
        val attachment = ObjectKeys.attachment(ws1, a1, message, AttachmentId(UUID.randomUUID()))
        shouldThrow<IllegalArgumentException> { make(listOf(ObjectKeys.raw(ws1, a1, message), attachment, attachment)) }
        make(listOf(ObjectKeys.raw(ws1, a1, message), attachment)).objectKeys.size shouldBe 2
        val tooMany = List(StorageAdmissionCandidate.MAX_KEYS) { ObjectKeys.attachment(ws1, a1, message, AttachmentId(UUID.randomUUID())) }
        shouldThrow<IllegalArgumentException> { make(listOf(ObjectKeys.raw(ws1, a1, message)) + tooMany) }
    }

    @Test
    fun `an event request is bounded and its copies are distinct`() {
        val c = candidate(ws1, a1)
        shouldThrow<IllegalArgumentException> { request(0, c) }
        shouldThrow<IllegalArgumentException> { request(-1, c) }
        shouldThrow<IllegalArgumentException> { request(10, c, c) } // the same message id twice
        shouldThrow<IllegalArgumentException> { request(10, *Array(51) { candidate(ws1, a1) }) }
        shouldThrow<IllegalArgumentException> { request(10, candidate(ws1, a1), candidate(ws1, a2, attachments = 1)) }
        shouldThrow<IllegalArgumentException> { StorageAdmissionRequest(10, listOf(c), " ", UUID.randomUUID()) }
        shouldThrow<IllegalArgumentException> { request(StorageAdmissionRequest.MAX_BYTES_PER_COPY + 1, c) }
        request(10, *Array(50) { candidate(ws1, a1) }).candidates.size shouldBe 50
    }
}
