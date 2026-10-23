package email.testinbox.application.usecase

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.InMemoryStorageAmbiguity
import email.testinbox.application.InMemoryStorageInspection
import email.testinbox.application.InMemoryStorageReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.IncompleteUpload
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.inbox.ReservationStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LifecycleTest {
    private val workspaceId = WorkspaceId(UUID.randomUUID())
    private val projectId = ProjectId(UUID.randomUUID())
    private lateinit var inboxes: InMemoryInboxRepository
    private lateinit var reservations: InMemoryReservations
    private lateinit var blobs: InMemoryBlobStore
    private lateinit var clock: MutableClock
    private val config =
        TestInboxConfig(
            mailDomain = "testinbox.local",
            expiryGrace = Duration.ofSeconds(30),
            exactCooldown = Duration.ofHours(24),
        )

    @BeforeEach
    fun setUp() {
        inboxes = InMemoryInboxRepository()
        reservations = InMemoryReservations()
        blobs = InMemoryBlobStore()
        clock = MutableClock(Instant.parse("2026-08-29T12:00:00Z"))
    }

    private fun inbox(
        mode: AddressMode = AddressMode.GENERATED,
        ttlSeconds: Long = 600,
    ): Inbox {
        val inbox =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                address = "${UUID.randomUUID()}@testinbox.local",
                addressMode = mode,
                state = InboxState.ACTIVE,
                createdAt = clock.now,
                expiresAt = clock.now.plusSeconds(ttlSeconds),
            )
        inboxes.insert(inbox)
        if (mode == AddressMode.EXACT) {
            reservations.reserve(
                ExactReservation(
                    UUID.randomUUID(),
                    workspaceId,
                    inbox.localPart,
                    inbox.id,
                    ReservationStatus.ACTIVE,
                    clock.now,
                    null,
                ),
                clock.now,
            )
        }
        return inbox
    }

    private fun sweeper(): ExpireInboxes = ExpireInboxes(inboxes, reservations, blobs, NoopTx, clock, config)

    @Test
    fun `full lifecycle - active to expiring to expired to hard-deleted with blob cleanup`() {
        val target = inbox()
        blobs.put(ObjectKeys.raw(workspaceId, target.id, MessageId(UUID.randomUUID())), byteArrayOf(1), "x")
        val other = inbox(ttlSeconds = 86_400)
        val otherKey = ObjectKeys.raw(workspaceId, other.id, MessageId(UUID.randomUUID()))
        blobs.put(otherKey, byteArrayOf(2), "x")

        clock.advanceSeconds(601)
        sweeper().sweep().markedExpiring shouldBe 1
        inboxes.inboxes[target.id]!!.state shouldBe InboxState.EXPIRING

        clock.advanceSeconds(31)
        val report = sweeper().sweep()
        report.markedExpired shouldBe 1
        report.hardDeleted shouldBe 1
        inboxes.inboxes.containsKey(target.id) shouldBe false
        // Deleting one inbox's prefix never touches another inbox's blobs.
        blobs.blobs.keys.toList() shouldBe listOf(otherKey)
        inboxes.inboxes[other.id]!!.state shouldBe InboxState.ACTIVE
    }

    @Test
    fun `an inbox whose prefix cannot be fully deleted keeps its rows and does not block the inboxes behind it`() {
        val stuck = inbox()
        val stuckKey = ObjectKeys.raw(workspaceId, stuck.id, MessageId(UUID.randomUUID()))
        blobs.put(stuckKey, byteArrayOf(1), "x")
        val fine = inbox()
        blobs.put(ObjectKeys.raw(workspaceId, fine.id, MessageId(UUID.randomUUID())), byteArrayOf(2), "x")
        blobs.failingPrefixes += ObjectKeys.inboxPrefix(workspaceId, stuck.id)

        clock.advanceSeconds(601)
        sweeper().sweep()
        clock.advanceSeconds(31)
        val report = sweeper().sweep()

        // Only the healthy inbox is hard-deleted; the stuck one keeps its rows AND its object
        // (rows gone with the object left would be bytes no figure describes), and is retried.
        report.hardDeleted shouldBe 1
        inboxes.inboxes.containsKey(fine.id) shouldBe false
        inboxes.inboxes.containsKey(stuck.id) shouldBe true
        blobs.blobs.keys.toList() shouldBe listOf(stuckKey)

        blobs.failingPrefixes.clear()
        sweeper().sweep().hardDeleted shouldBe 1
        inboxes.inboxes.containsKey(stuck.id) shouldBe false
        blobs.blobs.isEmpty() shouldBe true
    }

    @Test
    fun `exact inbox expiry starts the configured cooldown (ADR-021)`() {
        val target = inbox(mode = AddressMode.EXACT)
        clock.advanceSeconds(601)
        sweeper().sweep()
        clock.advanceSeconds(31)
        sweeper().sweep()
        val reservation = reservations.byLocalPart[target.localPart]!!
        reservation.status shouldBe ReservationStatus.COOLDOWN
        reservation.availableAt shouldBe clock.now.plus(config.exactCooldown)
    }

    @Test
    fun `delete inbox marks deleted and starts exact cooldown immediately`() {
        val target = inbox(mode = AddressMode.EXACT)
        val delete = DeleteInbox(inboxes, reservations, NoopTx, clock, config)
        delete.execute(workspaceId, target.id) shouldBe DeleteInbox.Result.Deleted
        inboxes.inboxes[target.id]!!.state shouldBe InboxState.DELETED
        reservations.byLocalPart[target.localPart]!!.status shouldBe ReservationStatus.COOLDOWN
        // Wrong workspace: NotFound, no state change.
        delete.execute(WorkspaceId(UUID.randomUUID()), target.id) shouldBe DeleteInbox.Result.NotFound
    }

    @Test
    fun `cooldown blocks re-reservation until elapsed, then reclaim succeeds`() {
        val target = inbox(mode = AddressMode.EXACT)
        DeleteInbox(inboxes, reservations, NoopTx, clock, config).execute(workspaceId, target.id)

        fun tryReserve(): ReserveOutcome =
            reservations.reserve(
                ExactReservation(
                    UUID.randomUUID(),
                    workspaceId,
                    target.localPart,
                    InboxId(UUID.randomUUID()),
                    ReservationStatus.ACTIVE,
                    clock.now,
                    null,
                ),
                clock.now,
            )
        tryReserve().shouldBeInstanceOf<ReserveOutcome.Conflict>().availableAt shouldBe
            clock.now.plus(config.exactCooldown)
        clock.now = clock.now.plus(config.exactCooldown).plusSeconds(1)
        tryReserve() shouldBe ReserveOutcome.Reserved
    }

    @Test
    fun `orphan sweep removes only unreferenced old blobs`() {
        val messages = InMemoryMessageRepository()
        val referencedId = MessageId(UUID.randomUUID())
        val orphanId = MessageId(UUID.randomUUID())
        val inboxId = InboxId(UUID.randomUUID())
        blobs.storedAtClock = clock
        val referencedKey = ObjectKeys.raw(workspaceId, inboxId, referencedId)
        val orphanKey = ObjectKeys.raw(workspaceId, inboxId, orphanId)
        blobs.put(referencedKey, byteArrayOf(1), "x")
        blobs.put(orphanKey, byteArrayOf(2), "x")
        // Only the referenced message has a DB row.
        val receiveFixture = ReceiveFixtureMessage(workspaceId, inboxId, referencedId)
        messages.appendVisible(receiveFixture.message)

        clock.advanceSeconds(7200)
        val freshOrphanKey = ObjectKeys.raw(workspaceId, inboxId, MessageId(UUID.randomUUID()))
        blobs.put(freshOrphanKey, byteArrayOf(3), "x") // too young to reap

        // ADR-035: a key is also protected by a live reservation, and by an
        // unresolved ambiguity for it. The sweep's single check sees all three.
        val ambiguity = InMemoryStorageAmbiguity(clock)
        val reservations = InMemoryStorageReservations(messages, ambiguity, clock)
        val reservedId = MessageId(UUID.randomUUID())
        val reservedKey = ObjectKeys.raw(workspaceId, inboxId, reservedId)
        val ambiguousKey = ObjectKeys.raw(workspaceId, inboxId, MessageId(UUID.randomUUID()))
        clock.advanceSeconds(-7200)
        blobs.put(reservedKey, byteArrayOf(4), "x")
        blobs.put(ambiguousKey, byteArrayOf(5), "x")
        clock.advanceSeconds(7200)
        reservations.rows[reservedId] =
            InMemoryStorageReservations.Row(
                reservedId,
                workspaceId,
                inboxId,
                listOf(reservedKey),
                1,
                "RELEASING",
                clock.instant(),
                clock.instant(),
                null,
            )
        ambiguity.record("node", ambiguousKey, 1, Duration.ofHours(1))
        val inspection = InMemoryStorageInspection(blobs, clock)

        val sweep = OrphanBlobSweep(blobs, reservations, ambiguity, ambiguity, inspection, clock, Duration.ofHours(1))
        sweep.sweep() shouldBe 1
        blobs.blobs.keys.toSet() shouldBe setOf(referencedKey, freshOrphanKey, reservedKey, ambiguousKey)
    }

    @Test
    fun `the orphan sweep removes a probe object its writer failed to delete, and leaves a fresh one alone`() {
        val messages = InMemoryMessageRepository()
        val ambiguity = InMemoryStorageAmbiguity(clock)
        val reservations = InMemoryStorageReservations(messages, ambiguity, clock)
        blobs.storedAtClock = clock
        val stale = "_probe/api-1/stale"
        blobs.put(stale, ByteArray(0), "x")
        clock.advanceSeconds(7200)
        val fresh = "_probe/api-1/fresh"
        blobs.put(fresh, ByteArray(0), "x")
        val inspection = InMemoryStorageInspection(blobs, clock)

        val sweep = OrphanBlobSweep(blobs, reservations, ambiguity, ambiguity, inspection, clock, Duration.ofHours(1))
        sweep.sweep() shouldBe 1
        blobs.blobs.keys.toSet() shouldBe setOf(fresh)
    }

    @Test
    fun `the orphan sweep aborts old incomplete multipart uploads, which TestInbox never starts`() {
        val messages = InMemoryMessageRepository()
        val ambiguity = InMemoryStorageAmbiguity(clock)
        val inspection = InMemoryStorageInspection(blobs, clock)
        val stale = IncompleteUpload("ws/inbox/msg/raw.eml", "u1", clock.instant().minusSeconds(7200))
        val fresh = IncompleteUpload("ws/inbox/msg2/raw.eml", "u2", clock.instant())
        inspection.incomplete += listOf(stale, fresh)

        OrphanBlobSweep(
            blobs,
            InMemoryStorageReservations(messages, ambiguity, clock),
            ambiguity,
            ambiguity,
            inspection,
            clock,
            Duration.ofHours(1),
        ).sweep()

        inspection.incomplete shouldBe listOf(fresh)
    }
}

/** Small helper building a minimal visible message for orphan-sweep tests. */
private class ReceiveFixtureMessage(
    workspaceId: WorkspaceId,
    inboxId: InboxId,
    messageId: MessageId,
) {
    val message =
        email.testinbox.domain.message.Message(
            id = messageId,
            workspaceId = workspaceId,
            inboxId = inboxId,
            receivedAt = Instant.parse("2026-08-29T12:00:00Z"),
            provider = "local-smtp",
            providerMessageId = null,
            envelopeFrom = null,
            envelopeTo = "x@testinbox.local",
            rawObjectKey = "k",
            rawSizeBytes = 1,
            contentFingerprint = "f",
            possibleDuplicateOfMessageId = null,
            parseStatus = email.testinbox.domain.message.ParseStatus.FAILED,
            parseError = "fixture",
            parsed = null,
            attachments = emptyList(),
        )
}
