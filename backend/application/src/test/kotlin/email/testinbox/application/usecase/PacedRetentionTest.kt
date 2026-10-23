package email.testinbox.application.usecase

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.DeletionDebtState
import email.testinbox.application.port.FilesystemObservation
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.StorageLedger
import email.testinbox.application.storage.DebtPacing
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.RetentionPacing
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Paced retention (filesystem-containment contract §5.4; TI-STORAGE-006E PR D):
 * message batches of exact ids, blobs proven gone before their rows, the next
 * batch only while the pacing allows it, fair across workspaces, a stuck batch
 * deferred alone, and the backlog metered. Unpaced, teardown is what it was
 * (`LifecycleTest`).
 */
class PacedRetentionTest {
    private val clock = MutableClock(Instant.parse("2026-10-08T12:00:00Z"))
    private val config =
        TestInboxConfig(mailDomain = "testinbox.local", expiryGrace = Duration.ofSeconds(30), exactCooldown = Duration.ofHours(24))
    private val events = mutableListOf<String>()

    /** The in-memory inboxes plus their messages, recording every teardown step in order. */
    private inner class Inboxes(
        private val delegate: InMemoryInboxRepository = InMemoryInboxRepository(),
    ) : InboxRepository by delegate,
        email.testinbox.application.port.InboxTeardown {
        val messages = LinkedHashMap<InboxId, MutableList<MessageId>>()

        fun expired(
            workspace: WorkspaceId,
            count: Int,
        ): Inbox {
            val inbox =
                Inbox(
                    InboxId(UUID.randomUUID()),
                    workspace,
                    ProjectId(UUID.randomUUID()),
                    "${UUID.randomUUID()}@testinbox.local",
                    AddressMode.GENERATED,
                    InboxState.EXPIRED,
                    clock.now.minusSeconds(7_200),
                    clock.now.minusSeconds(3_600),
                )
            delegate.insert(inbox)
            messages[inbox.id] = MutableList(count) { MessageId(UUID.randomUUID()) }
            return inbox
        }

        override fun messageIdsOf(
            inboxId: InboxId,
            limit: Int,
        ) = messages[inboxId].orEmpty().take(limit)

        override fun deleteMessages(
            inboxId: InboxId,
            ids: Collection<MessageId>,
        ): Int {
            events += "rows:${ids.size}"
            messages.getValue(inboxId).removeAll(ids.toSet())
            return ids.size
        }

        override fun hardDelete(id: InboxId) {
            events += "inbox:$id"
            delegate.hardDelete(id)
        }

        override fun teardownWaitingSince(id: InboxId): Instant = clock.now.minusSeconds(3_600)

        override fun oldestTeardownWaitingSince(): Instant? =
            if (delegate.findHardDeletable(Int.MAX_VALUE).isEmpty()) null else clock.now.minusSeconds(3_600)
    }

    private inner class Blobs : InMemoryBlobStore() {
        override fun deletePrefix(prefix: String) {
            events += "blobs:${prefix.count { it == '/' }}"
            super.deletePrefix(prefix)
        }
    }

    private class Backlog : InboxMetrics {
        var seconds = -1L

        override fun retentionBacklog(seconds: Long) {
            this.seconds = seconds
        }
    }

    private fun sweep(
        inboxes: Inboxes,
        pacing: RetentionPacing,
        blobs: InMemoryBlobStore = Blobs(),
        metrics: InboxMetrics = InboxMetrics.NOOP,
        batch: Int = 2,
    ) = ExpireInboxes(
        inboxes,
        InMemoryReservations(),
        blobs,
        NoopTx,
        clock,
        config,
        metrics,
        PacedTeardown(pacing, inboxes, batch),
    ).sweep()

    @Test
    fun `teardown is batches of exact ids - blobs before rows, the inbox row last, no prefix delete`() {
        val inboxes = Inboxes()
        val inbox = inboxes.expired(WorkspaceId(UUID.randomUUID()), count = 5)

        sweep(inboxes, pacing = { true }).hardDeleted shouldBe 1

        // Per-message prefixes have 3 slashes (ws/inbox/message/). No inbox prefix delete: rowless residue
        // is the orphan sweep's, which charges it by rule (P).
        events shouldBe
            listOf("blobs:3", "blobs:3", "rows:2", "blobs:3", "blobs:3", "rows:2", "blobs:3", "rows:1", "inbox:${inbox.id}")
    }

    @Test
    fun `while the pacing refuses, nothing is torn down - logical expiry stands, physical deletion waits`() {
        val inboxes = Inboxes()
        inboxes.expired(WorkspaceId(UUID.randomUUID()), count = 3)
        val backlog = Backlog()

        sweep(inboxes, pacing = { false }, metrics = backlog).hardDeleted shouldBe 0

        events shouldBe emptyList()
        backlog.seconds shouldBe 3_600
    }

    @Test
    fun `the pacing is asked before every batch, so teardown stops mid-inbox the moment D_est reaches D_budget`() {
        val inboxes = Inboxes()
        val inbox = inboxes.expired(WorkspaceId(UUID.randomUUID()), count = 6)
        var allowed = 2

        sweep(inboxes, pacing = { allowed-- > 0 }).hardDeleted shouldBe 0

        events.count { it.startsWith("rows:") } shouldBe 2
        inboxes.messages.getValue(inbox.id).size shouldBe 2
    }

    @Test
    fun `batches are taken fairly across workspaces, never one tenant's inboxes first`() {
        val inboxes = Inboxes()
        val a = WorkspaceId(UUID.randomUUID())
        val b = WorkspaceId(UUID.randomUUID())
        val a1 = inboxes.expired(a, 0)
        val a2 = inboxes.expired(a, 0)
        val b1 = inboxes.expired(b, 0)

        sweep(inboxes, pacing = { true })

        events.filter { it.startsWith("inbox:") } shouldBe listOf("inbox:${a1.id}", "inbox:${b1.id}", "inbox:${a2.id}")
    }

    @Test
    fun `a batch whose blobs cannot all be deleted keeps its rows and is deferred alone`() {
        val inboxes = Inboxes()
        val ws = WorkspaceId(UUID.randomUUID())
        val stuck = inboxes.expired(ws, 1)
        val fine = inboxes.expired(WorkspaceId(UUID.randomUUID()), 1)
        val blobs = Blobs()
        blobs.failingPrefixes += ObjectKeys.messagePrefix(ws, stuck.id, inboxes.messages.getValue(stuck.id).single())

        sweep(inboxes, pacing = { true }, blobs = blobs).hardDeleted shouldBe 1

        inboxes.messages.getValue(stuck.id).size shouldBe 1
        events.last() shouldBe "inbox:${fine.id}"
    }

    // --- the ALL pacing ------------------------------------------------------------------------

    private val model = FootprintModel.REFERENCE
    private val footprint = FootprintPolicy(model, FootprintAdmission.Limits(1, 0, 0, 0, 1, 0), "m", 0)

    private fun ledger(
        trash: Long?,
        debt: Pair<Long, Long>,
        fails: Boolean = false,
    ): StorageLedger =
        object : StorageLedger {
            override fun deletionDebt(): DeletionDebtState {
                if (fails) error("database gone")
                return DeletionDebtState(
                    debt.first,
                    debt.second,
                    trash?.let {
                        FilesystemObservation(
                            startedAt = clock.now,
                            observedAt = clock.now,
                            source = "m",
                            blockSizeBytes = 4096,
                            capacityBytes = 0,
                            usedBytes = 0,
                            availBytes = 0,
                            inodesTotal = 0,
                            inodesUsed = 0,
                            trashBytes = it,
                            minioSysBytes = 0,
                        )
                    },
                )
            }

            override fun compact(batch: Int) = error("unused")

            override fun state() = error("unused")

            override fun findDrift() = error("unused")

            override fun repairDrift() = error("unused")

            override fun compactDeletionDebt() = error("unused")

            override fun confirmTrust() = error("unused")
        }

    @Test
    fun `DebtPacing allows a batch exactly while W plus F of the debt stays within D_budget`() {
        val dEst = 5_000 + model.bound(100_000, 7)
        DebtPacing(ledger(5_000, 100_000L to 7L), footprint, dEst, Duration.ofHours(24), clock).mayTearDown(clock.now) shouldBe true
        DebtPacing(ledger(5_000, 100_000L to 7L), footprint, dEst - 1, Duration.ofHours(24), clock).mayTearDown(clock.now) shouldBe false
    }

    @Test
    fun `without an observation trash is unbounded, so nothing is paced through until T_max`() {
        DebtPacing(ledger(null, 0L to 0L), footprint, Long.MAX_VALUE / 4, Duration.ofHours(24), clock).mayTearDown(clock.now) shouldBe false
    }

    @Test
    fun `a refused inbox does not stop the sweep - the one behind it, past T_max, is still torn down`() {
        val inboxes = Inboxes()
        val first = inboxes.expired(WorkspaceId(UUID.randomUUID()), 0)
        inboxes.expired(WorkspaceId(UUID.randomUUID()), 0)
        var asked = 0
        // The pacing refuses the first inbox and admits the second (as T_max would).
        sweep(inboxes, pacing = { asked++ > 0 }).hardDeleted shouldBe 1
        inboxes.findHardDeletable(10).map { it.id } shouldBe listOf(first.id)
    }

    @Test
    fun `past T_max an inbox is torn down whatever D_est is, and an unreadable ledger pauses until then`() {
        val tMax = Duration.ofHours(24)
        val over = ledger(Long.MAX_VALUE / 4, 0L to 0L)
        DebtPacing(over, footprint, 1, tMax, clock).mayTearDown(clock.now.minus(tMax)) shouldBe false
        DebtPacing(over, footprint, 1, tMax, clock).mayTearDown(clock.now.minus(tMax).minusSeconds(1)) shouldBe true
        DebtPacing(ledger(0, 0L to 0L, fails = true), footprint, 1, tMax, clock).mayTearDown(clock.now) shouldBe false
    }
}
