package email.testinbox.application.usecase

import email.testinbox.application.InMemoryBlobStore
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryReservations
import email.testinbox.application.MutableClock
import email.testinbox.application.NoopTx
import email.testinbox.application.ObjectKeys
import email.testinbox.application.Sha256
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.ReserveOutcome
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.ExactReservation
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.inbox.ReservationStatus
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiScope
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

        val sweep = OrphanBlobSweep(blobs, messages, clock, Duration.ofHours(1))
        sweep.sweep() shouldBe 1
        blobs.blobs.keys.toSet() shouldBe setOf(referencedKey, freshOrphanKey)
    }

    @Test
    fun `api key authentication hashes the presented token and honors revocation`() {
        val plaintext = "tk_secret"
        val record =
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = workspaceId,
                projectId = projectId,
                keyHash = Sha256.hex(plaintext),
                scopes = setOf(ApiScope.INBOXES_WRITE),
                createdAt = clock.now,
                revokedAt = null,
            )
        var stored: ApiKey? = record
        val repo =
            object : ApiKeyRepository {
                override fun findActiveByHash(keyHash: String): ApiKey? = stored?.takeIf { it.keyHash == keyHash && it.revokedAt == null }
            }
        val auth = AuthenticateApiKey(repo)
        auth.authenticate(plaintext)?.workspaceId shouldBe workspaceId
        auth.authenticate("wrong") shouldBe null
        auth.authenticate("") shouldBe null
        stored = record.copy(revokedAt = clock.now)
        auth.authenticate(plaintext) shouldBe null
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
