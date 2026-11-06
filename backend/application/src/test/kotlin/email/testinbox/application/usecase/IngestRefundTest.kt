package email.testinbox.application.usecase

import email.testinbox.application.FakeRateLimiter
import email.testinbox.application.InMemoryInboxRepository
import email.testinbox.application.InMemoryMessageRepository
import email.testinbox.application.InMemoryStorage
import email.testinbox.application.MutableClock
import email.testinbox.application.RollbackTx
import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.LimitMetrics
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.ParsedMime
import email.testinbox.application.port.RateCharge
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.port.UploadRefusal
import email.testinbox.application.port.WorkspaceEligibility
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.application.storage.GuardedOutcome
import email.testinbox.application.storage.GuardedStorage
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.application.storage.StorageUnavailableException
import email.testinbox.application.storage.StorageUnavailableReason
import email.testinbox.application.storage.WriteSlots
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The INGEST refund (owner decision 2026-11-06, ADR-027 §4 as amended) at the
 * use-case boundary, with in-memory ports: which `451`s give the attempt's
 * charges back, exactly which charges, at most once, and that no outcome from
 * T1 on ever reaches a refund. The same properties against PostgreSQL and
 * MinIO are `IngestRefundIntegrationTest` (ingestion) and
 * `RateRefundTest` (persistence).
 */
class IngestRefundTest {
    /** Recording double: what the use case told the metrics port about refunds. */
    private class RecordingLimitMetrics : LimitMetrics {
        val refunds = mutableListOf<Triple<RateCategory, Boolean, Int>>()

        override fun rateRefund(
            category: RateCategory,
            refunded: Boolean,
            tokens: Int,
        ) {
            refunds += Triple(category, refunded, tokens)
        }
    }

    private val parser =
        object : MimeParser {
            override fun parse(raw: ByteArray): MimeParseResult =
                MimeParseResult.Parsed(
                    ParsedMime("s@example.com", "s@example.com", null, "hi", "body", null, emptyList(), emptyList(), emptyList()),
                )
        }

    /** One fresh world per scenario: ports, guarded storage, limiter and use case. */
    private inner class World(
        slots: ((InMemoryStorage) -> WriteSlots)? = null,
        hook: IngestSyncHook = IngestSyncHook.NONE,
        admissionStore: ((InMemoryStorage) -> StorageAdmissionStore)? = null,
        inboxRepository: ((InMemoryInboxRepository) -> InboxRepository)? = null,
        /** Runs whenever a recipient's workspace is checked: between the pre-resolution check and the guarded path. */
        val duringResolution: (World) -> Unit = {},
    ) {
        val clock = MutableClock(Instant.parse("2026-11-06T12:00:00Z"))
        val inboxes = InMemoryInboxRepository()
        val messages = InMemoryMessageRepository()
        val storage = InMemoryStorage(inboxes, messages, clock)
        val limiter = FakeRateLimiter()
        val metrics = RecordingLimitMetrics()
        val writeSlots: WriteSlots =
            slots?.invoke(storage) ?: WriteSlots(ambiguous = { storage.ambiguity.unresolvedFor(storage.node.nodeId) })
        val guarded: GuardedStorage =
            GuardedStorage(
                admission =
                    StorageAdmission(
                        admissionStore?.invoke(storage) ?: storage.admissionStore,
                        StorageCapacityPolicy.ADR_035_REFERENCE,
                        StorageEnforcement.OFF,
                    ),
                reservations = storage.reservations,
                ambiguity = storage.ambiguity,
                latch = storage.ambiguity,
                blobs = storage.blobs,
                inspection = storage.inspection,
                slots = writeSlots,
                breaker = storage.breaker,
                node = storage.node,
                transactions = RollbackTx(messages),
                clock = storage.databaseClock,
                hook = hook,
            )
        private val eligibility =
            WorkspaceEligibility { _ ->
                duringResolution(this)
                WorkspaceStatus.ACTIVE_PARTICIPANT
            }
        val useCase =
            ReceiveInboundDelivery(
                inboxRepository?.invoke(inboxes) ?: inboxes,
                messages,
                guarded,
                parser,
                limiter,
                clock,
                metrics = metrics,
                eligibility = eligibility,
            )

        fun inbox(
            address: String,
            workspace: WorkspaceId = WorkspaceId(UUID.randomUUID()),
        ): Inbox =
            Inbox(
                id = InboxId(UUID.randomUUID()),
                workspaceId = workspace,
                projectId = ProjectId(UUID.randomUUID()),
                address = address,
                addressMode = AddressMode.GENERATED,
                state = InboxState.ACTIVE,
                createdAt = clock.now,
                expiresAt = clock.now.plusSeconds(600),
            ).also { inboxes.insert(it) }

        fun deliver(vararg recipients: String) =
            useCase.execute(ReceiveInboundDelivery.Command("s@example.com", recipients.toList(), "MIME".toByteArray(), "local-smtp"))

        /** Holds this node's only slot, so the next event waits `W_slot` and is refused before T1. */
        fun exhaustSlots(workspace: WorkspaceId) = writeSlots.acquire(setOf(workspace))
    }

    private fun oneSlot(storage: InMemoryStorage) =
        WriteSlots(maxSlots = 1, perWorkspace = 1, waitTimeout = Duration.ofMillis(50)) {
            storage.ambiguity.unresolvedFor(storage.node.nodeId)
        }

    /** One copy's plan for the guarded path: its own raw.eml key, one byte. */
    private fun plan(inbox: Inbox): email.testinbox.application.storage.CopyPlan {
        val id = email.testinbox.domain.MessageId(UUID.randomUUID())
        val key =
            email.testinbox.application.ObjectKeys
                .raw(inbox.workspaceId, inbox.id, id)
        return email.testinbox.application.storage.CopyPlan(
            StorageAdmissionCandidate(id, inbox.workspaceId, inbox.id, listOf(key)),
            listOf(key to "x".toByteArray()),
        )
    }

    private fun bothBuckets(inbox: Inbox) =
        listOf(RateCharge(inbox.workspaceId, RateCategory.INGEST, inbox.id), RateCharge(inbox.workspaceId, RateCategory.INGEST))

    // --- refunded: proven before T1 ----------------------------------------------------------------------

    @Test
    fun `a slot-wait 451 gives back exactly the charges taken, once, and rethrows the same exception`() {
        val w = World(slots = ::oneSlot)
        val inbox = w.inbox("a@testinbox.local")
        w.exhaustSlots(inbox.workspaceId)

        val thrown = shouldThrow<StorageUnavailableException> { w.deliver("a@testinbox.local") }

        thrown.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        w.limiter.refunds shouldContainExactly listOf(bothBuckets(inbox))
        w.metrics.refunds shouldContainExactly listOf(Triple(RateCategory.INGEST, true, 2))
        w.messages.messages.shouldBeEmpty()
        w.storage.reservations.rows.size shouldBe 0
    }

    @Test
    fun `a latch or an open breaker that lands after the charges is refused before T1 and refunded`() {
        for ((name, trigger) in listOf<Pair<String, (World) -> Unit>>(
            "latched" to { it.storage.ambiguity.latchReason = "test" },
            "breaker" to { it.storage.breaker.trip(StorageBreaker.Kind.UNAVAILABLE) },
        )) {
            val w = World(duringResolution = trigger)
            val inbox = w.inbox("a@testinbox.local")

            val thrown = shouldThrow<StorageUnavailableException> { w.deliver("a@testinbox.local") }

            thrown.reason shouldBe if (name == "latched") StorageUnavailableReason.LATCHED else StorageUnavailableReason.BREAKER_OPEN
            w.limiter.refunds shouldContainExactly listOf(bothBuckets(inbox))
            w.storage.reservations.rows.size shouldBe 0
        }
    }

    @Test
    fun `a resolution failure after earlier copies were charged gives those charges back, and nothing for the failing one`() {
        val w =
            World(inboxRepository = { real ->
                object : InboxRepository by real {
                    override fun findReceivableByAddress(address: String): Inbox? =
                        if (address == "boom@testinbox.local") error("database blip") else real.findReceivableByAddress(address)
                }
            })
        val first = w.inbox("a@testinbox.local")

        shouldThrow<IllegalStateException> { w.deliver("a@testinbox.local", "boom@testinbox.local") }

        w.limiter.refunds shouldContainExactly listOf(bothBuckets(first))
    }

    @Test
    fun `a workspace spend that throws gives back the inbox token only - the unknown spend is never refunded`() {
        val w = World()
        val inbox = w.inbox("a@testinbox.local")
        val failing =
            object : email.testinbox.application.port.RateLimiter by w.limiter {
                override fun tryConsume(
                    workspaceId: WorkspaceId,
                    category: RateCategory,
                    inboxId: InboxId?,
                ) = if (inboxId == null) error("commit outcome unknown") else w.limiter.tryConsume(workspaceId, category, inboxId)
            }
        val useCase =
            ReceiveInboundDelivery(
                w.inboxes,
                w.messages,
                w.guarded,
                parser,
                failing,
                w.clock,
                eligibility = { WorkspaceStatus.ACTIVE_PARTICIPANT },
            )

        shouldThrow<IllegalStateException> {
            useCase.execute(
                ReceiveInboundDelivery.Command("s@example.com", listOf("a@testinbox.local"), "MIME".toByteArray(), "local-smtp"),
            )
        }

        w.limiter.refunds shouldContainExactly listOf(listOf(RateCharge(inbox.workspaceId, RateCategory.INGEST, inbox.id)))
    }

    @Test
    fun `owner test 4 - a multi-recipient event refunds exactly the charges taken, per bucket`() {
        val w = World(slots = ::oneSlot)
        val wsA = WorkspaceId(UUID.randomUUID())
        val wsB = WorkspaceId(UUID.randomUUID())
        val a1 = w.inbox("a1@testinbox.local", wsA)
        val a2 = w.inbox("a2@testinbox.local", wsA)
        val b1 = w.inbox("b1@testinbox.local", wsB)
        val b2 = w.inbox("b2@testinbox.local", wsB)
        // a2: its inbox token is taken, then its workspace refuses it (never charged at the workspace).
        // b2: its own inbox budget refuses it (charged nowhere).
        var workspaceASpends = 0
        val limiter =
            object : email.testinbox.application.port.RateLimiter by w.limiter {
                override fun tryConsume(
                    workspaceId: WorkspaceId,
                    category: RateCategory,
                    inboxId: InboxId?,
                ): email.testinbox.application.port.RateDecision {
                    val refused = inboxId == b2.id || (inboxId == null && workspaceId == wsA && ++workspaceASpends > 1)
                    return email.testinbox.application.port
                        .RateDecision(category, !refused, 1, 0, null)
                }
            }
        val useCase =
            ReceiveInboundDelivery(
                w.inboxes,
                w.messages,
                w.guarded,
                parser,
                limiter,
                w.clock,
                eligibility = { WorkspaceStatus.ACTIVE_PARTICIPANT },
            )
        w.exhaustSlots(wsA)

        shouldThrow<StorageUnavailableException> {
            useCase.execute(
                ReceiveInboundDelivery.Command(
                    "s@example.com",
                    listOf(
                        "a1@testinbox.local",
                        "a2@testinbox.local",
                        "b1@testinbox.local",
                        "b2@testinbox.local",
                        "nobody@testinbox.local",
                    ),
                    "MIME".toByteArray(),
                    "local-smtp",
                ),
            )
        }.reason shouldBe StorageUnavailableReason.SLOT_WAIT

        w.limiter.refunds.single() shouldContainExactlyInAnyOrder
            listOf(
                RateCharge(wsA, RateCategory.INGEST, a1.id),
                RateCharge(wsA, RateCategory.INGEST),
                RateCharge(wsA, RateCategory.INGEST, a2.id),
                RateCharge(wsB, RateCategory.INGEST, b1.id),
                RateCharge(wsB, RateCategory.INGEST),
            )
    }

    @Test
    fun `a failed refund is never retried, keeps the charges, and the 451's cause is unchanged`() {
        val w = World(slots = ::oneSlot)
        val inbox = w.inbox("a@testinbox.local")
        w.limiter.refundFailure = IllegalStateException("refund commit outcome unknown")
        w.exhaustSlots(inbox.workspaceId)

        val thrown = shouldThrow<StorageUnavailableException> { w.deliver("a@testinbox.local") }

        thrown.reason shouldBe StorageUnavailableReason.SLOT_WAIT
        thrown.suppressed.single() shouldBeSameInstanceAs w.limiter.refundFailure
        w.limiter.refunds.size shouldBe 1
        w.metrics.refunds shouldContainExactly listOf(Triple(RateCategory.INGEST, false, 2))
    }

    // --- never refunded: T1 invoked, or the event completed ----------------------------------------------

    /** One post-T1 failure: how to build its world, what it is called, the exception it must surface as. */
    private class PostT1(
        val name: String,
        val world: () -> World,
        val arm: (World) -> Unit = {},
    )

    private val postT1 =
        listOf(
            PostT1("T1 lock timeout", {
                World(admissionStore = { _ ->
                    object : StorageAdmissionStore {
                        override fun <R : Any> admit(
                            scope: email.testinbox.application.port.StorageAdmissionScope,
                            decide: (
                                email.testinbox.application.port.StorageUsageSnapshot,
                            ) -> email.testinbox.application.port.StorageAdmissionPlan<R>,
                        ): R = throw StorageAdmissionUnavailableException("lock_timeout", RuntimeException("55P03"))
                    }
                })
            }),
            PostT1("T1 failing for any other reason", {
                World(admissionStore = { _ ->
                    object : StorageAdmissionStore {
                        override fun <R : Any> admit(
                            scope: email.testinbox.application.port.StorageAdmissionScope,
                            decide: (
                                email.testinbox.application.port.StorageUsageSnapshot,
                            ) -> email.testinbox.application.port.StorageAdmissionPlan<R>,
                        ): R = error("connection lost during T1: commit status unknown")
                    }
                })
            }),
            PostT1("after T1, before uploads", {
                World(
                    hook =
                        object : IngestSyncHook {
                            override fun afterAdmission(): Unit = error("died after T1")
                        },
                )
            }),
            PostT1("write deadline missed (definitive refusal)", { World() }, {
                it.storage.blobs.nextOutcomes +=
                    UploadOutcome.Refused(UploadRefusal.DENIED)
            }),
            PostT1("upload ambiguous (commit uncertainty)", { World() }, {
                it.storage.blobs.nextOutcomes += UploadOutcome.Ambiguous(AmbiguityKind.TIMEOUT)
            }),
            PostT1("T2 failure before commit", {
                World(
                    hook =
                        object : IngestSyncHook {
                            override fun beforeCommit(): Unit = error("died before T2")
                        },
                )
            }),
            PostT1("T2 failure inside the commit transaction", {
                World(
                    hook =
                        object : IngestSyncHook {
                            override fun inCommitAfterReservationLock(): Unit = error("T2 commit outcome unknown")
                        },
                )
            }),
        )

    @Test
    fun `owner test 3 (unit) - no outcome from T1 on reaches a refund, and the charges stand`() {
        for (case in postT1) {
            val w = case.world()
            w.inbox("a@testinbox.local")
            case.arm(w)

            runCatching { w.deliver("a@testinbox.local") }.isFailure shouldBe true

            check(w.limiter.refunds.isEmpty()) { "${case.name}: refunded ${w.limiter.refunds}" }
            check(w.metrics.refunds.isEmpty()) { "${case.name}: reported a refund" }
            // The charges were taken: inbox then workspace, exactly as before refunds existed.
            w.limiter.calls.size shouldBe 2
        }
    }

    @Test
    fun `the guarded path classifies every post-T1 failure as thrown, never as refused before T1`() {
        for (case in postT1) {
            val w = case.world()
            val inbox = w.inbox("a@testinbox.local")
            case.arm(w)
            val plan = plan(inbox)

            val outcome = runCatching { w.guarded.attempt(1, listOf(plan)) { ids -> ids.associate { it.messageId to true } } }

            check(outcome.isFailure) { "${case.name}: returned ${outcome.getOrNull()} instead of throwing" }
        }
    }

    @Test
    fun `the guarded path hands back a pre-T1 refusal as an outcome, and ingest still throws it`() {
        val w = World(slots = ::oneSlot)
        val inbox = w.inbox("a@testinbox.local")
        w.exhaustSlots(inbox.workspaceId)
        val plan = plan(inbox)

        val outcome = w.guarded.attempt(1, listOf(plan)) { error("never reached") }

        outcome.shouldBeInstanceOf<GuardedOutcome.RefusedBeforeT1>()
        (outcome.cause as StorageUnavailableException).reason shouldBe StorageUnavailableReason.SLOT_WAIT
        shouldThrow<StorageUnavailableException> { w.guarded.ingest(1, listOf(plan)) { error("never reached") } }.reason shouldBe
            StorageUnavailableReason.SLOT_WAIT
    }

    @Test
    fun `a delivered event and a fully rate-limited one are never refunded`() {
        val w = World()
        w.inbox("a@testinbox.local")
        w.deliver("a@testinbox.local").accepted.size shouldBe 1
        w.limiter.exhausted += RateCategory.INGEST
        w.deliver("a@testinbox.local").rateLimitedRecipients shouldBe 1

        w.limiter.refunds.shouldBeEmpty()
    }

    // --- the refund token: at most once ------------------------------------------------------------------

    @Test
    fun `the refund token of one attempt is claimed at most once, even by racing threads`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val inbox = InboxId(UUID.randomUUID())
        val charges = IngestCharges().apply { took(IngestCharges.CopyCharge(ws, inbox, workspaceTaken = true)) }

        val threads = 16
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val claims =
                (1..threads).map {
                    pool.submit<List<RateCharge>?> {
                        start
                            .await(
                                10,
                                TimeUnit.SECONDS,
                            ).let { charges.claimRefund() }
                    }
                }
            start.countDown()
            val winners = claims.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            winners shouldContainExactly listOf(listOf(RateCharge(ws, RateCategory.INGEST, inbox), RateCharge(ws, RateCategory.INGEST)))
        } finally {
            pool.shutdownNow()
        }
        charges.claimRefund() shouldBe null
    }

    @Test
    fun `an attempt whose charges were kept can never be refunded afterwards`() {
        val charges =
            IngestCharges().apply {
                took(
                    IngestCharges.CopyCharge(WorkspaceId(UUID.randomUUID()), InboxId(UUID.randomUUID()), true),
                )
            }
        charges.keep()
        charges.claimRefund() shouldBe null
        shouldThrow<IllegalStateException> {
            charges.took(IngestCharges.CopyCharge(WorkspaceId(UUID.randomUUID()), InboxId(UUID.randomUUID()), true))
        }
    }

    @Test
    fun `an attempt that took nothing has nothing to refund`() {
        IngestCharges().claimRefund() shouldBe null
    }
}
