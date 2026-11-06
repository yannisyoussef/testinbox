package email.testinbox.application.usecase

import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowAttempt
import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowLaneStatus
import email.testinbox.application.port.EscrowNeed
import email.testinbox.application.port.EscrowPath
import email.testinbox.application.port.EscrowPlan
import email.testinbox.application.port.EscrowRow
import email.testinbox.application.port.EscrowScope
import email.testinbox.application.port.EscrowScopeRef
import email.testinbox.application.port.EscrowView
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageEscrowMetrics
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.port.StorageReservationDraft
import email.testinbox.application.storage.FootprintPolicy
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.EscrowFit
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.StorageRefusalReason
import java.time.Duration

/**
 * The slot-lane escrow's fast path, as part of the ONE `StorageAdmission` use case
 * (ADR-037 "one storage T1/T2 writer"; structural-admission-design.md §4.2, §7).
 * M1 PROTOTYPE (ADR-038 PROPOSED): only the storage benchmark constructs it.
 *
 * The decision ([decide]) is mode a's, restricted to what grants can prove:
 * - ownership from the owner the lock statement read after its locks, never a scope
 *   row: a missing or foreign inbox fails T1 closed exactly as mode a
 *   ([StorageAdmissionInputException]);
 * - lifecycle from EXACT workspace rows only (an inactive row has no grant, so it is
 *   always short, so always refilled, so always exact);
 * - per copy, in envelope order, narrowest scope first: an EXACT row decides with
 *   mode a's own running rule (refusal or admission); a non-exact row can only
 *   admit, from its grant; anything else escalates the WHOLE event;
 * - the lane must cover the full-envelope priced prefix through the last eligible
 *   copy, or the event escalates. `SERVICE_CAPACITY` is never decided here.
 */
class EscrowAdmission(
    private val store: StorageEscrowStore,
    val inputs: EscrowInputs,
    val metrics: StorageEscrowMetrics = StorageEscrowMetrics.NONE,
) {
    /** The fast path's result, or null when the event escalated (nothing was written). */
    internal fun fastPath(
        request: StorageAdmissionRequest,
        lane: Int,
        footprint: FootprintPolicy,
    ): StorageAdmissionResult? {
        val model = footprint.model
        val f = request.bytesPerCopy
        val n =
            request.candidates
                .first()
                .objectKeys.size
                .toLong()
        val copyFootprint = model.bound(f, n)
        val perWorkspace = request.candidates.groupingBy { it.workspaceId }.eachCount()
        val scope =
            EscrowScope(
                nodeId = request.nodeId,
                lane = lane,
                workspaces =
                    perWorkspace.map { (ws, k) ->
                        EscrowNeed(ws.value, Math.multiplyExact(f, k.toLong()), Math.multiplyExact(copyFootprint, k.toLong()))
                    },
                inboxes = request.candidates.map { EscrowNeed(it.inboxId.value, f, copyFootprint) },
                inputs = inputs,
            )
        var seen: EscrowView? = null
        val attempt =
            store.tryAdmit(scope) { view ->
                seen = view
                decide(request, view, footprint)
            }
        val view = seen
        if (view != null) report(view)
        return when (attempt) {
            is EscrowAttempt.Decided -> {
                val refilled = view != null && (view.workspaces.values + view.inboxes.values).any { it.refill != null }
                metrics.path(if (refilled) EscrowPath.SCOPE_REFILL else EscrowPath.FAST)
                attempt.outcome
            }

            is EscrowAttempt.Escalated -> {
                metrics.escalation(attempt.reason)
                null
            }
        }
    }

    private fun report(view: EscrowView) {
        metrics.scopeLock(Duration.ofNanos(view.scopeWaitMicros * NANOS_PER_MICRO))
        for ((kind, rows) in listOf(EscrowScopeRef.Kind.W to view.workspaces.values, EscrowScopeRef.Kind.I to view.inboxes.values)) {
            rows.forEach { row ->
                row.refill?.let { metrics.inlineRefill(kind, it, Duration.ofNanos((row.refillMicros ?: 0) * NANOS_PER_MICRO)) }
            }
        }
    }

    internal companion object {
        private const val NANOS_PER_MICRO = 1_000L

        /** §4.2 step 4. Pure: everything it reads is in [view]. */
        @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount") // one branch per rule of mode a, in its order
        fun decide(
            request: StorageAdmissionRequest,
            view: EscrowView,
            footprint: FootprintPolicy,
        ): EscrowPlan<StorageAdmissionResult> {
            // Mode a's verifyOwnership, from the owner read after the locks (correctness 2).
            request.candidates.forEach { candidate ->
                val owner = view.owners[candidate.inboxId]
                if (owner != candidate.workspaceId) {
                    throw StorageAdmissionInputException(
                        if (owner ==
                            null
                        ) {
                            "inbox does not exist"
                        } else {
                            "inbox belongs to another workspace"
                        },
                    )
                }
            }
            when (view.lane.status) {
                EscrowLaneStatus.OK -> Unit
                EscrowLaneStatus.MISSING -> return EscrowPlan.Escalate(EscalationReason.LANE_MISSING)
                EscrowLaneStatus.STALE -> return EscrowPlan.Escalate(EscalationReason.LANE_STALE)
                EscrowLaneStatus.NOT_READY -> return EscrowPlan.Escalate(EscalationReason.NOT_READY)
                EscrowLaneStatus.BINDING -> return EscrowPlan.Escalate(EscalationReason.BINDING_MISMATCH)
            }
            val model = footprint.model
            val f = request.bytesPerCopy
            val n =
                request.candidates
                    .first()
                    .objectKeys.size
                    .toLong()
            val copy = FootprintAdmission.Load(f, n)
            val copyFootprint = model.bound(f, n)

            // Lifecycle first, as mode a: only an EXACT row may say "inactive".
            val inactive = HashSet<WorkspaceId>()
            for ((ws, row) in view.workspaces) {
                if (row.active == false) {
                    if (!row.exact) return EscrowPlan.Escalate(EscalationReason.SPEND_MISMATCH)
                    inactive += ws
                }
            }
            val eligible = request.candidates.indices.filter { request.candidates[it].workspaceId !in inactive }

            // The lane: the full-envelope priced prefix through the last eligible copy (ADR-037 A1).
            if (eligible.isNotEmpty()) {
                val payloadAvail = view.lane.payloadAvail ?: return EscrowPlan.Escalate(EscalationReason.LANE_MISSING)
                val footprintAvail = view.lane.footprintAvail ?: return EscrowPlan.Escalate(EscalationReason.LANE_MISSING)
                val prefix = EscrowFit.lanePrefix(model, f, n, request.envelopePositions[eligible.last()])
                if (!EscrowFit.laneFits(model, payloadAvail, footprintAvail, prefix)) {
                    return EscrowPlan.Escalate(EscalationReason.LANE_SHORT)
                }
            }

            val remainingInbox = HashMap<InboxId, EscrowFit.Remaining>()
            val remainingWorkspace = HashMap<WorkspaceId, EscrowFit.Remaining>()
            val runningBytes = HashMap<WorkspaceId, Long>()
            val runningObjects = HashMap<WorkspaceId, Long>()
            val deadline = view.t0.plus(StorageAdmission.WRITE_WINDOW)
            val decided = HashMap<Int, StorageAdmissionDecision>()
            for (index in eligible) {
                val candidate = request.candidates[index]
                val inbox = view.inboxes[candidate.inboxId] ?: return EscrowPlan.Escalate(EscalationReason.SPEND_MISMATCH)
                val workspace = view.workspaces[candidate.workspaceId] ?: return EscrowPlan.Escalate(EscalationReason.SPEND_MISMATCH)
                val refusal: StorageRefusalReason? =
                    // INBOX, narrowest first.
                    when (inboxVerdict(inbox, f, remainingInbox, candidate.inboxId)) {
                        Verdict.ESCALATE -> {
                            return EscrowPlan.Escalate(EscalationReason.SPEND_MISMATCH)
                        }

                        Verdict.REFUSE -> {
                            StorageRefusalReason.INBOX_LIMIT
                        }

                        Verdict.FITS -> {
                            when (
                                workspaceVerdict(
                                    workspace,
                                    f,
                                    copy,
                                    copyFootprint,
                                    model,
                                    remainingWorkspace,
                                    candidate.workspaceId,
                                    runningBytes[candidate.workspaceId] ?: 0,
                                    runningObjects[candidate.workspaceId] ?: 0,
                                )
                            ) {
                                WorkspaceVerdict.ESCALATE -> return EscrowPlan.Escalate(EscalationReason.SPEND_MISMATCH)
                                WorkspaceVerdict.PAYLOAD -> StorageRefusalReason.WORKSPACE_LIMIT
                                WorkspaceVerdict.FOOTPRINT -> StorageRefusalReason.WORKSPACE_FOOTPRINT_LIMIT
                                WorkspaceVerdict.FITS -> null
                            }
                        }
                    }
                decided[index] =
                    if (refusal != null) {
                        StorageAdmissionDecision.Refused(candidate, refusal)
                    } else {
                        // Admitted: spend from the non-exact grants, add to mode a's running totals.
                        if (!inbox.exact) {
                            remainingInbox[candidate.inboxId] =
                                checkNotNull(remainingOf(inbox, remainingInbox[candidate.inboxId]).spend(f, 0))
                        }
                        if (!workspace.exact) {
                            remainingWorkspace[candidate.workspaceId] =
                                checkNotNull(remainingOf(workspace, remainingWorkspace[candidate.workspaceId]).spend(f, copyFootprint))
                        }
                        runningBytes.merge(candidate.workspaceId, f, Math::addExact)
                        runningObjects.merge(candidate.workspaceId, n, Math::addExact)
                        StorageAdmissionDecision.Admitted(
                            candidate = candidate,
                            reservation =
                                StorageReservationDraft(
                                    messageId = candidate.messageId,
                                    workspaceId = candidate.workspaceId,
                                    inboxId = candidate.inboxId,
                                    objectKeys = candidate.objectKeys,
                                    bytes = f,
                                    createdAt = view.t0,
                                    writeDeadlineAt = deadline,
                                    nodeId = request.nodeId,
                                    generation = request.generation,
                                ),
                        )
                    }
            }
            val decisions = request.candidates.indices.map { decided[it] ?: StorageAdmissionDecision.Inactive(request.candidates[it]) }
            return EscrowPlan.Spend(
                reservations = decisions.filterIsInstance<StorageAdmissionDecision.Admitted>().map { it.reservation },
                outcome = StorageAdmissionResult(decisions, view.t0),
            )
        }

        private enum class Verdict { FITS, REFUSE, ESCALATE }

        private enum class WorkspaceVerdict { FITS, PAYLOAD, FOOTPRINT, ESCALATE }

        private fun remainingOf(
            row: EscrowRow,
            current: EscrowFit.Remaining?,
        ): EscrowFit.Remaining = current ?: EscrowFit.Remaining(checkNotNull(row.payloadAvail), row.footprintAvail)

        /** One copy per inbox per event, so mode a's running inbox total is always 0 here. */
        private fun inboxVerdict(
            row: EscrowRow,
            f: Long,
            remaining: Map<InboxId, EscrowFit.Remaining>,
            id: InboxId,
        ): Verdict {
            if (row.exact) {
                val used = row.usedBytes ?: return Verdict.ESCALATE
                val limit = row.limitPayload ?: return Verdict.ESCALATE
                return if (EscrowFit.exceedsPayload(used, 0, f, limit)) Verdict.REFUSE else Verdict.FITS
            }
            if (row.payloadAvail == null || row.active != true) return Verdict.ESCALATE
            return if (remainingOf(row, remaining[id]).spend(f, 0) != null) Verdict.FITS else Verdict.ESCALATE
        }

        @Suppress("LongParameterList", "ReturnCount")
        private fun workspaceVerdict(
            row: EscrowRow,
            f: Long,
            copy: FootprintAdmission.Load,
            copyFootprint: Long,
            model: email.testinbox.domain.storage.FootprintModel,
            remaining: Map<WorkspaceId, EscrowFit.Remaining>,
            id: WorkspaceId,
            runningBytes: Long,
            runningObjects: Long,
        ): WorkspaceVerdict {
            if (row.exact) {
                val used = row.usedBytes ?: return WorkspaceVerdict.ESCALATE
                val limit = row.limitPayload ?: return WorkspaceVerdict.ESCALATE
                if (EscrowFit.exceedsPayload(used, runningBytes, f, limit)) return WorkspaceVerdict.PAYLOAD
                val footprintLimit = row.limitFootprint ?: return WorkspaceVerdict.FITS
                val exceeded =
                    EscrowFit.exceedsFootprint(
                        model,
                        used,
                        row.usedObjects ?: return WorkspaceVerdict.ESCALATE,
                        FootprintAdmission.Load(row.chargeBytes ?: 0, row.chargeObjects ?: 0),
                        FootprintAdmission.Load(runningBytes, runningObjects),
                        copy,
                        footprintLimit,
                    )
                return if (exceeded) WorkspaceVerdict.FOOTPRINT else WorkspaceVerdict.FITS
            }
            if (row.payloadAvail == null || row.active != true) return WorkspaceVerdict.ESCALATE
            return if (remainingOf(row, remaining[id]).spend(f, copyFootprint) != null) WorkspaceVerdict.FITS else WorkspaceVerdict.ESCALATE
        }
    }
}
