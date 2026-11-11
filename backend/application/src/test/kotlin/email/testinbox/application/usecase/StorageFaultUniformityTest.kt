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
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageProtocol
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Raw bytes `SLOW` park in the parser until [parseResumes]: a slow, sender-controlled parse. */
    private val parsing = CountDownLatch(1)
    private val parseResumes = CountDownLatch(1)

    private val parser =
        object : MimeParser {
            override fun parse(raw: ByteArray): MimeParseResult {
                if (raw.contentEquals(SLOW)) {
                    parsing.countDown()
                    parseResumes.await(10, TimeUnit.SECONDS) shouldBe true
                }
                return MimeParseResult.Parsed(
                    ParsedMime("s@example.com", "s@example.com", null, "hi", "body", null, emptyList(), emptyList(), emptyList()),
                )
            }
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
    private fun reply(
        recipient: String,
        via: ReceiveInboundDelivery = receive,
        raw: ByteArray = "MIME".toByteArray(),
    ): String =
        try {
            via.execute(ReceiveInboundDelivery.Command("s@example.com", listOf(recipient), raw, "local-smtp"))
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

    // --- a 403 trips only inside the write window (review of 95d3cea) -----------------------------

    /** The fake storage answers a 403 at [offset] from T1's `t0` (the reservation's write deadline is `t0 + E`). */
    private fun deniedAt(offset: Duration) {
        storage.blobs.onPut = { upload -> clock.now = upload.signedAt.plus(offset) }
        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.DENIED)
    }

    @Test
    fun `a 403 more than eps_max before the deadline is credentials or policy - it trips, and everyone gets the same 451`() {
        deniedAt(StorageProtocol.WRITE_WINDOW.minus(StorageProtocol.EPSILON_MAX).minusMillis(1))
        reply(known.address) shouldBe "451 UPLOAD_FAILED"
        breaker.openKinds shouldBe setOf(StorageBreaker.Kind.DENIED)
        setOf(reply(unknown), reply(known.address)) shouldBe setOf("451 BREAKER_OPEN")
    }

    @Test
    fun `a 403 within eps_max of the deadline may be an expired URL by storage's clock - that event's 451, no global trip`() {
        // Storage judges the URL by its own clock, up to ε_max ahead of the database's (ADR-035 §5).
        deniedAt(StorageProtocol.WRITE_WINDOW.minus(StorageProtocol.EPSILON_MAX))
        reply(known.address) shouldBe "451 UPLOAD_FAILED"
        breaker.isOpen shouldBe false
        reply(unknown) shouldBe "250"
        reply(known.address) shouldBe "250"
    }

    // --- the real-event trial is claimed at the gate (review of 95d3cea) ---------------------------

    @Test
    fun `in the real-event phase one event claims the trial at the gate, and every concurrent event gets the same 451`() {
        val otherKnown = provision("other@testinbox.local", WorkspaceId(UUID.randomUUID()))
        val holding = AtomicBoolean(false)
        val uploading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val held =
            ReceiveInboundDelivery(
                inboxes,
                messages,
                storage.guarded(
                    RollbackTx(messages),
                    StorageEnforcement.ALL,
                    policy,
                    hook =
                        object : IngestSyncHook {
                            override fun beforeUpload(key: String) {
                                if (holding.getAndSet(false)) {
                                    uploading.countDown()
                                    proceed.await(10, TimeUnit.SECONDS) shouldBe true
                                }
                            }
                        },
                ),
                parser,
                FakeRateLimiter(),
                clock,
                eligibility = WorkspaceEligibility { WorkspaceStatus.ACTIVE_PARTICIPANT },
            )
        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.QUOTA)
        reply(known.address, held) shouldBe "451 UPLOAD_FAILED"
        backoffExpires() // the witness will pass: the real-event phase begins with the next event

        holding.set(true)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val claimant = pool.submit<String> { reply(known.address, held) }
            uploading.await(10, TimeUnit.SECONDS) shouldBe true // it holds the claim, and is uploading now
            val others = listOf(otherKnown.address, unknown).associateWith { reply(it, held) }
            others shouldBe mapOf(otherKnown.address to "451 BREAKER_OPEN", unknown to "451 BREAKER_OPEN")
            proceed.countDown()
            claimant.get(10, TimeUnit.SECONDS) shouldBe "250"
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
        breaker.isOpen shouldBe false
        setOf(reply(otherKnown.address, held), reply(unknown, held)) shouldBe setOf("250")
    }

    @Test
    fun `an event that passed the gate is never overtaken by a later claimant - the race of the real-event phase`() {
        // Each event is held while it resolves its recipients (between the gate and the upload), so the
        // order is forced: A reaches the gate first, then C, then B; A uploads only after C resolved.
        val otherKnown = provision("other@testinbox.local", WorkspaceId(UUID.randomUUID()))
        val resolving = mapOf(known.address to CountDownLatch(1), otherKnown.address to CountDownLatch(1))
        val resume = mapOf(known.address to CountDownLatch(1), otherKnown.address to CountDownLatch(1))
        val holdA = AtomicBoolean(false)
        val holdC = AtomicBoolean(false)
        val holding =
            object : email.testinbox.application.port.InboxRepository by inboxes {
                override fun findReceivableByAddress(address: String): Inbox? {
                    val hold =
                        if (address == known.address) {
                            holdA
                        } else if (address == otherKnown.address) {
                            holdC
                        } else {
                            null
                        }
                    if (hold?.getAndSet(false) == true) {
                        resolving.getValue(address).countDown()
                        resume.getValue(address).await(10, TimeUnit.SECONDS) shouldBe true
                    }
                    return inboxes.findReceivableByAddress(address)
                }
            }
        val raced =
            ReceiveInboundDelivery(
                holding,
                messages,
                storage.guarded(RollbackTx(messages), StorageEnforcement.ALL, policy),
                parser,
                FakeRateLimiter(),
                clock,
                eligibility = WorkspaceEligibility { WorkspaceStatus.ACTIVE_PARTICIPANT },
            )
        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.QUOTA)
        reply(known.address, raced) shouldBe "451 UPLOAD_FAILED"
        backoffExpires() // the next event's witness passes: the real-event phase begins

        holdA.set(true)
        holdC.set(true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<String> { reply(known.address, raced) }
            resolving.getValue(known.address).await(10, TimeUnit.SECONDS) shouldBe true // A passed the gate
            val gateOfC = CountDownLatch(1)
            val c =
                pool.submit<String> {
                    try {
                        reply(otherKnown.address, raced)
                    } finally {
                        gateOfC.countDown()
                    }
                }
            // C is either refused at the gate (done) or resolving (held): either way, past its gate.
            val cAtGate =
                java.util.concurrent.CompletableFuture.anyOf(
                    java.util.concurrent.CompletableFuture
                        .runAsync { resolving.getValue(otherKnown.address).await(10, TimeUnit.SECONDS) },
                    java.util.concurrent.CompletableFuture
                        .runAsync { gateOfC.await(10, TimeUnit.SECONDS) },
                )
            cAtGate.get(10, TimeUnit.SECONDS)
            val b = reply(unknown, raced) // an unknown-only event at the same moment
            resume.getValue(known.address).countDown() // A goes on to its upload
            resume.getValue(otherKnown.address).countDown()
            val replies = mapOf("C" to c.get(10, TimeUnit.SECONDS), "B" to b)
            withClue("$replies") { replies.values.toSet() shouldBe setOf("451 BREAKER_OPEN") }
            a.get(10, TimeUnit.SECONDS) shouldBe "250" // the claimant: the real event that confirmed recovery
        } finally {
            resume.values.forEach { it.countDown() }
            pool.shutdownNow()
        }
    }

    // --- the claim is taken after parsing (review of c292340) --------------------------------------

    @Test
    fun `a slow parse never holds the real-event claim - another event takes it meanwhile`() {
        storage.blobs.nextOutcomes += UploadOutcome.Refused(UploadRefusal.QUOTA)
        reply(known.address) shouldBe "451 UPLOAD_FAILED"
        backoffExpires()
        reply(unknown) shouldBe "250" // the witness passed and the unknown-only event gave the claim back
        breaker.awaitsRealEvent shouldBe true

        val otherKnown = provision("other@testinbox.local", WorkspaceId(UUID.randomUUID()))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val slow = pool.submit<String> { reply(known.address, raw = SLOW) }
            parsing.await(10, TimeUnit.SECONDS) shouldBe true // a hostile message, still being parsed
            reply(otherKnown.address) shouldBe "250" // took the claim, confirmed recovery
            breaker.isOpen shouldBe false
            parseResumes.countDown()
            slow.get(10, TimeUnit.SECONDS) shouldBe "250"
        } finally {
            parseResumes.countDown()
            pool.shutdownNow()
        }
        delivered(known.address) shouldBe 1
        delivered(otherKnown.address) shouldBe 1
    }

    private companion object {
        const val FAULT_DEPTH = 32
        val SLOW = "SLOW".toByteArray()
    }
}
