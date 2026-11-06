package email.testinbox.application.usecase

import email.testinbox.application.FakeRateLimiter
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryStorage
import email.testinbox.application.MutableClock
import email.testinbox.application.RollbackTx
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.ParsedMime
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.port.UploadRefusal
import email.testinbox.application.port.WorkspaceEligibility
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-025 with ADR-035 §8 and §12: a global storage fault is answered the
 * same way for every event, before any recipient resolves, for the whole
 * fault, its recovery included. The half-open trial is the non-tenant witness
 * on the pre-resolution path, never a tenant event, so a known recipient, an
 * unknown one and one at its ceiling cannot be told apart by the reply while
 * storage is broken. On a manual breaker clock: no sleeps.
 *
 * The reply class here is what the SMTP adapter derives: a result is `250`,
 * any [StorageUnavailableException] the same `451` (the transcripts
 * themselves are compared in `SmtpAntiOracleTest`).
 */
class StorageFaultUniformityTest {
    private var nanos = 0L
    private val breaker = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { nanos })
    private val clock = MutableClock(Instant.parse("2026-11-06T12:00:00Z"))
    private val inboxes = InMemoryInboxRepository()
    private val messages = InMemoryMessageRepository()
    private val storage = InMemoryStorage(inboxes, messages, clock, breaker)

    private val parser =
        object : MimeParser {
            override fun parse(raw: ByteArray): MimeParseResult =
                MimeParseResult.Parsed(
                    ParsedMime("s@example.com", "s@example.com", null, "hi", "body", null, emptyList(), emptyList(), emptyList()),
                )
        }

    /** Workspace ceiling 100 bytes; a copy costs 4. The full workspace is already at its ceiling. */
    private val policy = StorageCapacityPolicy(100, InboxShare.of("1"), 10_000, 0)
    private val known = provision("known@testinbox.local", WorkspaceId(UUID.randomUUID()))
    private val fullWorkspace = WorkspaceId(UUID.randomUUID())
    private val atCeiling = provision("full@testinbox.local", fullWorkspace).also { storage.committed[fullWorkspace] = 100 }
    private val unknown = "nobody@testinbox.local"

    private val receive =
        ReceiveInboundDelivery(
            inboxes,
            messages,
            storage.guarded(RollbackTx(messages), StorageEnforcement.ALL, policy),
            parser,
            FakeRateLimiter(),
            clock,
            eligibility = WorkspaceEligibility { WorkspaceStatus.ACTIVE_PARTICIPANT },
        )

    private fun provision(
        address: String,
        workspace: WorkspaceId,
    ): Inbox =
        Inbox(
            id = InboxId(UUID.randomUUID()),
            workspaceId = workspace,
            projectId = ProjectId(UUID.randomUUID()),
            address = address,
            addressMode = AddressMode.GENERATED,
            state = InboxState.ACTIVE,
            createdAt = clock.now,
            expiresAt = clock.now.plusSeconds(3_600),
        ).also { inboxes.insert(it) }

    /** `250`, or `451` with the internal reason (never on the wire: every 451 is the same bytes). */
    private fun reply(recipient: String): String =
        try {
            receive.execute(ReceiveInboundDelivery.Command("s@example.com", listOf(recipient), "MIME".toByteArray(), "local-smtp"))
            "250"
        } catch (e: StorageUnavailableException) {
            "451 ${e.reason}"
        }

    /** The backoff expires: the breaker is half-open, and its next caller runs the trial. */
    private fun backoffExpires() {
        nanos += breaker.currentBackoff.toNanos()
    }

    private fun delivered(address: String) = messages.messages.count { it.envelopeTo == address }

    /** A persistent fault: every upload attempt gets [outcome] until [storageRecovers]. */
    private fun storageFails(outcome: UploadOutcome) = repeat(FAULT_DEPTH) { storage.blobs.nextOutcomes += outcome }

    /** How many uploads the fault answered: tenant uploads attempted while storage was broken. */
    private fun faultedUploads() = FAULT_DEPTH - storage.blobs.nextOutcomes.size

    private fun storageRecovers() {
        storage.blobs.nextOutcomes.clear()
        storage.inspection.witnessSucceeds = true
    }

    @Test
    fun `half-open with the store down - known and unknown get the same pre-resolution 451 until a witness succeeds`() {
        storageFails(UploadOutcome.NotStarted)
        storage.inspection.witnessSucceeds = false
        reply(known.address) shouldBe "451 UPLOAD_FAILED" // the onset: the first event to reach storage finds it gone
        breaker.openKinds shouldBe setOf(StorageBreaker.Kind.UNAVAILABLE)
        val reservations = storage.reservations.rows.size

        val backoffs = mutableListOf<Duration>()
        repeat(4) { window ->
            backoffExpires()
            // Whoever comes first runs the witness, before resolving anything; the other meets the re-tripped breaker.
            val order = if (window % 2 == 0) listOf(unknown, known.address) else listOf(known.address, unknown)
            val replies = order.associateWith { reply(it) }
            withClue("window $window: $replies") {
                replies.values.toSet() shouldBe setOf("451 BREAKER_OPEN")
            }
            backoffs += breaker.currentBackoff
        }
        backoffs shouldBe listOf(30L, 60L, 120L, 120L).map { Duration.ofSeconds(it) } // the existing schedule
        storage.inspection.witnessKeys.size shouldBe 4 // one proof per window, and never a tenant upload
        storage.inspection.witnessKeys.forEach { it.startsWith("_probe/") shouldBe true }
        storage.reservations.rows.size shouldBe reservations // no T1 ran for either recipient
        faultedUploads() shouldBe 1 // the onset's only

        storageRecovers()
        backoffExpires()
        reply(unknown) shouldBe "250"
        breaker.isOpen shouldBe false // the witness closed it, not a tenant event
        reply(known.address) shouldBe "250"
        delivered(known.address) shouldBe 1
    }

    @Test
    fun `QUOTA half-open - an at-ceiling event and an under-ceiling event get the same 451 until a witness succeeds`() {
        storageFails(UploadOutcome.Refused(UploadRefusal.QUOTA))
        // A bucket at or over its hard quota refuses even the zero-byte witness.
        storage.inspection.witnessSucceeds = false
        reply(known.address) shouldBe "451 UPLOAD_FAILED"
        breaker.openKinds shouldBe setOf(StorageBreaker.Kind.QUOTA)

        repeat(3) { window ->
            backoffExpires()
            val order = if (window % 2 == 0) listOf(atCeiling.address, known.address) else listOf(known.address, atCeiling.address)
            val replies = order.associateWith { reply(it) }
            withClue("window $window: $replies") {
                replies.values.toSet() shouldBe setOf("451 BREAKER_OPEN")
            }
        }
        storage.reservations.refusals.size shouldBe 0 // the ceiling was never even consulted
        faultedUploads() shouldBe 1

        storageRecovers()
        backoffExpires()
        reply(atCeiling.address) shouldBe "250" // refused by its ceiling, as always: it does not spend the real-event trial
        breaker.awaitsRealEvent shouldBe true
        reply(known.address) shouldBe "250" // the real event (ADR-035 §8) confirms recovery
        breaker.isOpen shouldBe false
        delivered(known.address) shouldBe 1
        storage.reservations.refusals[atCeiling.id] shouldBe 1
    }

    @Test
    fun `a 403 trips the breaker - known and unknown get the same pre-resolution 451, and the witness alone recovers it`() {
        storageFails(UploadOutcome.Refused(UploadRefusal.DENIED)) // revoked credentials keep refusing
        storage.inspection.witnessSucceeds = false // the witness signs with the same credentials
        reply(known.address) shouldBe "451 UPLOAD_FAILED"

        // From the very next event, before any backoff: the same answer whoever the recipient is.
        listOf(unknown, known.address, atCeiling.address).associateWith { reply(it) } shouldBe
            mapOf(unknown to "451 BREAKER_OPEN", known.address to "451 BREAKER_OPEN", atCeiling.address to "451 BREAKER_OPEN")
        breaker.openKinds shouldBe setOf(StorageBreaker.Kind.DENIED)

        repeat(2) {
            backoffExpires()
            setOf(reply(known.address), reply(unknown)) shouldBe setOf("451 BREAKER_OPEN")
        }
        faultedUploads() shouldBe 1

        // A 403 never latches: the next witness reopens it, with no real event needed.
        storageRecovers()
        backoffExpires()
        reply(unknown) shouldBe "250"
        breaker.isOpen shouldBe false
        reply(known.address) shouldBe "250"
        delivered(known.address) shouldBe 1
    }

    @Test
    fun `a passed witness leaves a real-event kind its real-event trial, and a failed one re-trips with the doubled backoff`() {
        // ADR-035 §8: MinIO's lagging quota can accept the zero-byte witness while real uploads still fail.
        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.QUOTA)
        reply(known.address)
        backoffExpires()
        reply(unknown) shouldBe "250" // the witness passed before resolution
        breaker.awaitsRealEvent shouldBe true

        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.QUOTA)
        reply(known.address) shouldBe "451 UPLOAD_FAILED" // the real event was the trial, and it failed
        breaker.currentBackoff shouldBe Duration.ofSeconds(30)
        breaker.awaitsRealEvent shouldBe false
        // And the gate is uniform again at once.
        setOf(reply(unknown), reply(known.address), reply(atCeiling.address)) shouldBe setOf("451 BREAKER_OPEN")
        storage.inspection.witnessKeys.shouldNotBeEmpty()
    }

    private companion object {
        const val FAULT_DEPTH = 32
    }
}
