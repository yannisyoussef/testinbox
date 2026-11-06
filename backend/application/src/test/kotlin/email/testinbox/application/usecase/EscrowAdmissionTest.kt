package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowAttempt
import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowKeeperState
import email.testinbox.application.port.EscrowLane
import email.testinbox.application.port.EscrowLaneStatus
import email.testinbox.application.port.EscrowPath
import email.testinbox.application.port.EscrowPlan
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.EscrowRow
import email.testinbox.application.port.EscrowScope
import email.testinbox.application.port.EscrowScopeRef
import email.testinbox.application.port.EscrowView
import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageEscrowMetrics
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.application.storage.WorkspaceStorageBudget
import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * The slot-lane escrow fast path's decision (ADR-038 PROPOSED; §4.2 step 4) and the
 * use case's sequence, as pure unit tests. The decisive property: on EXACT rows the
 * fast path decides every copy as mode a's own rules do (correctness 3), and on
 * anything it cannot prove it escalates, never refuses.
 */
class EscrowAdmissionTest {
    private val model = FootprintModel.REFERENCE
    private val huge = Long.MAX_VALUE / 8
    private val footprint =
        FootprintPolicy(model, FootprintAdmission.Limits(huge, 0, 0, 0, huge, 0), "monitor", capacityBytes = 0)
    private val t0 = Instant.parse("2026-11-06T12:00:00Z")
    private val node = "smtp"
    private val generation = UUID.randomUUID()

    private fun candidate(
        ws: WorkspaceId,
        ib: InboxId,
        attachments: Int = 1,
    ): StorageAdmissionCandidate {
        val m = MessageId(UUID.randomUUID())
        return StorageAdmissionCandidate(
            m,
            ws,
            ib,
            listOf(ObjectKeys.raw(ws, ib, m)) +
                List(attachments) { ObjectKeys.attachment(ws, ib, m, email.testinbox.domain.AttachmentId(UUID.randomUUID())) },
        )
    }

    private fun exact(
        used: Long,
        limit: Long,
        usedObjects: Long? = null,
        footprintLimit: Long? = null,
        charge: FootprintAdmission.Load = FootprintAdmission.Load.ZERO,
        active: Boolean = true,
    ) = EscrowRow(
        exact = true,
        refill = null,
        refillMicros = null,
        payloadAvail = maxOf(0, limit - used),
        footprintAvail = footprintLimit?.let { maxOf(0, it - model.bound(used + charge.bytes, (usedObjects ?: 0) + charge.objects)) },
        limitPayload = limit,
        limitFootprint = footprintLimit,
        usedBytes = used,
        usedObjects = usedObjects,
        chargeBytes = charge.bytes,
        chargeObjects = charge.objects,
        active = active,
    )

    private fun grant(
        payload: Long,
        footprint: Long? = null,
    ) = EscrowRow(false, null, null, payload, footprint, null, null, null, null, null, null, true)

    private fun view(
        request: StorageAdmissionRequest,
        workspaces: Map<WorkspaceId, EscrowRow>,
        inboxes: Map<InboxId, EscrowRow>,
        lane: EscrowLane = EscrowLane(EscrowLaneStatus.OK, huge, huge),
        owners: Map<InboxId, WorkspaceId?> = request.candidates.associate { it.inboxId to it.workspaceId },
    ) = EscrowView(t0, lane, workspaces, inboxes, owners, 0, 0)

    private fun shape(plan: EscrowPlan<StorageAdmissionResult>): List<String> =
        when (plan) {
            is EscrowPlan.Escalate -> listOf("escalate:${plan.reason}")
            is EscrowPlan.Spend -> plan.outcome.decisions.map(::label)
        }

    private fun label(d: StorageAdmissionDecision) =
        when (d) {
            is StorageAdmissionDecision.Admitted -> "admitted"
            is StorageAdmissionDecision.Refused -> d.reason.name
            is StorageAdmissionDecision.Inactive -> "inactive"
        }

    @Test
    fun `P-D on exact rows the fast path decides every copy exactly as mode a's rules, on random truths`() {
        val random = Random(38)
        repeat(2000) { case ->
            val workspaces = List(2) { WorkspaceId(UUID.randomUUID()) }
            val copies = 1 + random.nextInt(6)
            val f = 1L + random.nextLong(5_000)
            val attachments = random.nextInt(3)
            val n = 1L + attachments
            val candidates = List(copies) { candidate(workspaces[random.nextInt(2)], InboxId(UUID.randomUUID()), attachments) }
            val request = StorageAdmissionRequest(f, candidates, node, generation)
            val maxStored = 20_000L + random.nextLong(30_000)
            val policy = StorageCapacityPolicy(maxStored, InboxShare.of("0.5"), huge, 0)
            val truth =
                workspaces.associateWith {
                    Triple(random.nextLong(30_000), random.nextLong(20), random.nextBoolean()) // used, objects, shared
                }
            val share = workspaces.associateWith { 10_000L + random.nextLong(20_000) }
            val footShare = workspaces.associateWith { model.bound(5_000, 3) + random.nextLong(200_000) }
            val charge = workspaces.associateWith { FootprintAdmission.Load(random.nextLong(2_000), random.nextLong(3)) }
            val inboxUsed = candidates.associate { it.inboxId to random.nextLong(15_000) }

            // Mode a's snapshot of that truth.
            val snapshot =
                StorageUsageSnapshot(
                    t0 = t0,
                    global = StorageUsage(0, 0),
                    workspaces = truth.mapValues { (_, t) -> StorageUsage(t.first, 0, t.second, 0) },
                    inboxes =
                        candidates.associate {
                            it.inboxId to InboxStorageUsage(it.workspaceId, StorageUsage(inboxUsed.getValue(it.inboxId), 0))
                        },
                    footprint = ObservedFootprint(0, 0, 0, 0, true, 0, 1, 0, model.blockSizeBytes, huge, "monitor"),
                    budgets =
                        truth.filterValues { it.third }.mapValues { (ws, _) ->
                            WorkspaceStorageBudget(share.getValue(ws), footShare.getValue(ws), charge.getValue(ws))
                        },
                )
            val modeA =
                StorageAdmissionRules
                    .decide(
                        policy,
                        StorageEnforcement.ALL,
                        f,
                        n,
                        candidates.map { StorageAdmissionRules.Placement(it.workspaceId, it.inboxId) },
                        snapshot,
                        footprint,
                    ).map { if (it.admitted) "admitted" else checkNotNull(it.ceiling).name }

            // The same truth as EXACT escrow rows, refilled by V28-proto's formulas.
            val wsLimit = truth.mapValues { (ws, t) -> if (t.third) minOf(maxStored, share.getValue(ws)) else maxStored }
            val rows =
                truth.mapValues { (ws, t) ->
                    val shared = t.third
                    exact(
                        t.first,
                        wsLimit.getValue(ws),
                        t.second,
                        if (shared) footShare.getValue(ws) else null,
                        if (shared) charge.getValue(ws) else FootprintAdmission.Load.ZERO,
                    )
                }
            val inboxRows =
                candidates.associate {
                    it.inboxId to exact(inboxUsed.getValue(it.inboxId), policy.inboxShare.floorOf(wsLimit.getValue(it.workspaceId)))
                }
            val escrow = shape(EscrowAdmission.decide(request, view(request, rows, inboxRows), footprint))
            withClue("case $case") { escrow shouldBe modeA }
        }
    }

    @Test
    fun `a grant only admits, and a grant too short escalates the whole event, never refuses`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val ib = List(3) { InboxId(UUID.randomUUID()) }
        val request = StorageAdmissionRequest(100, ib.map { candidate(ws, it, 0) }, node, generation)
        val copyF = model.bound(100, 1)
        val fits = view(request, mapOf(ws to grant(300, 3 * copyF)), ib.associateWith { grant(100) })
        shape(EscrowAdmission.decide(request, fits, footprint)) shouldBe List(3) { "admitted" }
        val short = view(request, mapOf(ws to grant(250, 3 * copyF)), ib.associateWith { grant(100) })
        shape(EscrowAdmission.decide(request, short, footprint)) shouldBe listOf("escalate:SPEND_MISMATCH")
        val footShort = view(request, mapOf(ws to grant(300, 3 * copyF - 1)), ib.associateWith { grant(100) })
        shape(EscrowAdmission.decide(request, footShort, footprint)) shouldBe listOf("escalate:SPEND_MISMATCH")
    }

    @Test
    fun `the lane must cover the full-envelope priced prefix, and every lane state that is not OK escalates with its reason`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val ib = InboxId(UUID.randomUUID())
        // One resolved copy at envelope position 4: mode a prices 5 copies against the global ceilings.
        val request = StorageAdmissionRequest(100, listOf(candidate(ws, ib, 0)), node, generation, envelopePositions = listOf(4))
        val rows = mapOf(ws to grant(1_000)) to mapOf(ib to grant(1_000))
        val prefixF = model.bound(500, 5)

        fun on(lane: EscrowLane) = shape(EscrowAdmission.decide(request, view(request, rows.first, rows.second, lane), footprint))
        on(EscrowLane(EscrowLaneStatus.OK, 500, prefixF)) shouldBe
            listOf("admitted")
        on(EscrowLane(EscrowLaneStatus.OK, 499, prefixF)) shouldBe
            listOf("escalate:LANE_SHORT")
        on(EscrowLane(EscrowLaneStatus.OK, 500, prefixF - 1)) shouldBe
            listOf("escalate:LANE_SHORT")
        mapOf(
            EscrowLaneStatus.MISSING to EscalationReason.LANE_MISSING,
            EscrowLaneStatus.STALE to EscalationReason.LANE_STALE,
            EscrowLaneStatus.NOT_READY to EscalationReason.NOT_READY,
            EscrowLaneStatus.BINDING to EscalationReason.BINDING_MISMATCH,
        ).forEach { (status, reason) ->
            on(EscrowLane(status, huge, huge)) shouldBe
                listOf("escalate:$reason")
        }
    }

    @Test
    fun `ownership comes from the owner read, never a scope row, and an inactive workspace is decided only from an exact row`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val other = WorkspaceId(UUID.randomUUID())
        val ib = InboxId(UUID.randomUUID())
        val request = StorageAdmissionRequest(100, listOf(candidate(ws, ib, 0)), node, generation)
        val rows = mapOf(ws to grant(1_000))
        shouldThrow<StorageAdmissionInputException> {
            EscrowAdmission.decide(request, view(request, rows, mapOf(ib to grant(1_000)), owners = mapOf(ib to other)), footprint)
        }
        shouldThrow<StorageAdmissionInputException> {
            EscrowAdmission.decide(request, view(request, rows, mapOf(ib to grant(1_000)), owners = mapOf(ib to null)), footprint)
        }
        val inbox = mapOf(ib to grant(1_000))
        shape(EscrowAdmission.decide(request, view(request, mapOf(ws to exact(0, 1_000, 0, active = false)), inbox), footprint)) shouldBe
            listOf("inactive")
        shape(EscrowAdmission.decide(request, view(request, mapOf(ws to grant(0).copy(active = false)), inbox), footprint)) shouldBe
            listOf("escalate:SPEND_MISMATCH")
    }

    // --- the use case's sequence -------------------------------------------------------------------------

    private class RecordingMetrics : StorageEscrowMetrics {
        val paths = mutableListOf<EscrowPath>()
        val escalations = mutableListOf<EscalationReason>()

        override fun path(path: EscrowPath) {
            paths += path
        }

        override fun escalation(reason: EscalationReason) {
            escalations += reason
        }
    }

    private class FakeEscrow(
        val answer: (EscrowScope) -> EscrowAttempt<*>,
    ) : StorageEscrowStore {
        val scopes = mutableListOf<EscrowScope>()

        @Suppress("UNCHECKED_CAST")
        override fun <R : Any> tryAdmit(
            scope: EscrowScope,
            decide: (EscrowView) -> EscrowPlan<R>,
        ): EscrowAttempt<R> {
            scopes += scope
            return answer(scope) as EscrowAttempt<R>
        }

        override fun refillGlobal(notReady: (StorageUsageSnapshot) -> String?) = EscrowRegrant(0, 0, 0, false, "fake")

        override fun keeperState(
            nodeId: String,
            horizon: Duration,
        ) = EscrowKeeperState(0, 0, 0, false, null)

        override fun scopeCandidates(
            lowPayload: Long,
            lowFootprint: Long,
            limit: Int,
        ): List<EscrowScopeRef> = emptyList()

        override fun refillScope(ref: EscrowScopeRef) = true
    }

    /** Mode a's store, deciding on an empty, ready snapshot and recording the plan it was given. */
    private inner class FakeModeA : StorageAdmissionStore {
        val plans = mutableListOf<StorageAdmissionPlan<*>>()

        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R {
            val snapshot =
                StorageUsageSnapshot(
                    t0,
                    StorageUsage(0, 0),
                    scope.workspaceIds.associateWith { StorageUsage(0, 0) },
                    scope.inboxIds.associateWith { InboxStorageUsage(scope.workspaceIds.first(), StorageUsage(0, 0)) },
                    ObservedFootprint(0, 0, 0, 0, true, 0, 1, 0, model.blockSizeBytes, huge, "monitor"),
                )
            return decide(snapshot).also { plans += it }.outcome
        }
    }

    private val policy = StorageCapacityPolicy(1L shl 40, InboxShare.of("1"), 1L shl 50, 0)
    private val inputs = EscrowInputs(policy.workspaceLimitBytes, policy.inboxShare.value, model)

    @Test
    fun `StorageAdmission owns the sequence - fast when decided, mode a's slow path with the readiness verdict when escalated`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val request = StorageAdmissionRequest(100, listOf(candidate(ws, InboxId(UUID.randomUUID()))), node, generation, lane = 3)
        val decided = StorageAdmissionResult(emptyList(), t0)
        val metrics = RecordingMetrics()

        val modeA = FakeModeA()
        val fast = FakeEscrow { EscrowAttempt.Decided(decided) }
        StorageAdmission(modeA, policy, StorageEnforcement.ALL, footprint = footprint, escrow = EscrowAdmission(fast, inputs, metrics))
            .admit(request) shouldBe decided
        modeA.plans.size shouldBe 0
        fast.scopes.single().lane shouldBe 3

        val escalated = FakeEscrow { EscrowAttempt.Escalated(EscalationReason.LANE_STALE) }
        val slow = FakeModeA()
        val result =
            StorageAdmission(
                slow,
                policy,
                StorageEnforcement.ALL,
                footprint = footprint,
                escrow = EscrowAdmission(escalated, inputs, metrics),
            ).admit(request)
        result.decisions.map(::label) shouldBe listOf("admitted")
        slow.plans.single().escrowNotReady shouldBe null
        metrics.paths shouldBe listOf(EscrowPath.FAST, EscrowPath.SLOW)
        metrics.escalations shouldBe listOf(EscalationReason.LANE_STALE)
    }

    @Test
    fun `without a slot index, or without escrow, the use case is mode a and never touches the escrow store`() {
        val ws = WorkspaceId(UUID.randomUUID())
        val escrow = FakeEscrow { error("must not be called") }
        val modeA = FakeModeA()
        val admission =
            StorageAdmission(modeA, policy, StorageEnforcement.ALL, footprint = footprint, escrow = EscrowAdmission(escrow, inputs))
        val request = StorageAdmissionRequest(100, listOf(candidate(ws, InboxId(UUID.randomUUID()))), node, generation)
        admission.admit(request).decisions.size shouldBe 1
        modeA.plans.single().escrowNotReady shouldBe null
        escrow.scopes.size shouldBe 0
    }

    @Test
    fun `the escrow refuses to run outside ALL, without a footprint, or with inputs that are not mode a's policy`() {
        val escrow = EscrowAdmission(FakeEscrow { error("unused") }, inputs)

        fun build(
            mode: StorageEnforcement,
            fp: FootprintPolicy?,
            with: EscrowAdmission,
        ) = StorageAdmission(FakeModeA(), policy, mode, footprint = fp, escrow = with)
        shouldThrow<IllegalArgumentException> { build(StorageEnforcement.TENANT_LIMITS, footprint, escrow) }
        shouldThrow<IllegalArgumentException> { build(StorageEnforcement.ALL, null, escrow) }
        val other = EscrowAdmission(FakeEscrow { error("unused") }, inputs.copy(maxStoredBytes = 1))
        shouldThrow<IllegalArgumentException> { build(StorageEnforcement.ALL, footprint, other) }
    }
}
