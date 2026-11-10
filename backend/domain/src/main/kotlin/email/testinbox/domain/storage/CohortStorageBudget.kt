package email.testinbox.domain.storage

import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.BetaCohortPolicy

/** ADR-037 beta ceilings. No runtime configuration may increase these. */
object ParticipantStorageShare {
    const val PAYLOAD_BYTES: Long = 128L * 1024 * 1024
    const val FOOTPRINT_BYTES: Long = 256L * 1024 * 1024
    val allPayloadBytes: Long = Math.multiplyExact(BetaCohortPolicy.MAX_PARTICIPANTS.toLong(), PAYLOAD_BYTES)
    val allFootprintBytes: Long = Math.multiplyExact(BetaCohortPolicy.MAX_PARTICIPANTS.toLong(), FOOTPRINT_BYTES)
}

/** An explicit bounded owner/synthetic/legacy workspace, never an implicit unlimited exception. */
data class LegacyStorageShare(
    val payloadBytes: Long,
    val footprintBytes: Long,
) {
    init {
        require(payloadBytes > 0 && footprintBytes > 0) { "every legacy workspace needs positive finite shares" }
    }
}

/**
 * Checked, conservative feasibility arithmetic, not an activation authorization.
 * All thirty shares are reserved even when none is admitted. The footprint
 * demand sums whole-workspace bounds (including attributed pending deletion)
 * and a whole worst-case transient envelope; subadditivity makes that no less
 * conservative than bounding their combined bytes/objects once. Finalization
 * is separate, as required by (G). Unattributed debt/trash and (C)'s other
 * reserves are additional, never assumed to be zero or paid by a participant.
 */
object CohortStorageBudget {
    /** Every field is mandatory; an undeclared deployment cannot manufacture a proof. */
    data class Declarations(
        val model: FootprintModel,
        val globalPayloadLimitBytes: Long,
        val payloadFinalizeBudgetBytes: Long,
        val filesystem: FootprintAdmission.Limits,
        val legacy: Map<WorkspaceId, LegacyStorageShare>,
        val unattributedDebtFootprintBytes: Long,
        val trashAllowanceBytes: Long,
        val worstEnvelope: FootprintAdmission.Load,
    ) {
        init {
            require(globalPayloadLimitBytes > 0 && payloadFinalizeBudgetBytes >= 0)
            require(unattributedDebtFootprintBytes >= 0 && trashAllowanceBytes >= 0)
            require(worstEnvelope.bytes > 0 && worstEnvelope.objects > 0)
        }
    }

    data class Demand(
        val reservedPayloadBytes: Long,
        val reservedFootprintBytes: Long,
        val envelopeFootprintBytes: Long,
        val requiredGlobalPayloadBytes: Long,
        val requiredGlobalFootprintBytes: Long,
        val requiredFilesystemBytes: Long,
    )

    enum class Boundary { PAYLOAD, GLOBAL_FOOTPRINT, FILESYSTEM }

    sealed interface Result {
        data class Fits(
            val demand: Demand,
        ) : Result

        data class Insufficient(
            val demand: Demand,
            val boundaries: Set<Boundary>,
        ) : Result

        /** Missing declarations and arithmetic overflow never mean empty or ready. */
        data object Unproven : Result
    }

    /**
     * The partition bound of the share-partitioned T1 (ADR-035 amendment note,
     * PROPOSED): the largest total of reserved shares — all thirty participant
     * places plus every legacy share, payload and footprint separately — for
     * which [assess] is [Result.Fits] under these declarations. By construction
     *
     * ```
     * assess(d) is Fits  ⇔  30·P + Σ legacy payload   ≤ payloadBytes
     *                     ∧  30·F + Σ legacy footprint ≤ footprintBytes
     * ```
     *
     * (`PartitionCapacityTest` proves the equivalence by property), so the
     * database can re-check non-overcommitment from the share rows alone, in
     * T1's own snapshot, without the declarations. [Declarations.legacy] is
     * ignored: the bound is what the declarations leave for shares.
     */
    data class PartitionCapacity(
        val payloadBytes: Long,
        val footprintBytes: Long,
    ) {
        init {
            require(payloadBytes > 0 && footprintBytes > 0) { "a partition bound is positive, was $payloadBytes / $footprintBytes" }
        }
    }

    /** [PartitionCapacity] for [declarations], or null when it overflows or leaves no room for any share. */
    fun partitionCapacity(declarations: Declarations): PartitionCapacity? =
        try {
            val d = declarations
            val event = d.model.bound(d.worstEnvelope.bytes, d.worstEnvelope.objects)
            val payload =
                Math.subtractExact(Math.subtractExact(d.globalPayloadLimitBytes, d.worstEnvelope.bytes), d.payloadFinalizeBudgetBytes)
            val policy =
                Math.subtractExact(Math.subtractExact(d.filesystem.globalFootprintLimitBytes, event), d.filesystem.finalizeBudgetBytes)
            val filesystem =
                listOf(
                    event,
                    d.filesystem.finalizeBudgetBytes,
                    d.unattributedDebtFootprintBytes,
                    d.trashAllowanceBytes,
                    d.filesystem.probeBudgetBytes,
                    d.filesystem.metadataBudgetBytes,
                    d.filesystem.operationalReserveBytes,
                ).fold(d.filesystem.capacityBytes, Math::subtractExact)
            val footprint = minOf(policy, filesystem)
            if (payload <= 0 || footprint <= 0) null else PartitionCapacity(payload, footprint)
        } catch (_: ArithmeticException) {
            null
        }

    fun assess(declarations: Declarations?): Result {
        if (declarations == null) return Result.Unproven
        return try {
            val demand = demand(declarations)
            val exceeded =
                buildSet {
                    if (demand.requiredGlobalPayloadBytes > declarations.globalPayloadLimitBytes) add(Boundary.PAYLOAD)
                    if (demand.requiredGlobalFootprintBytes > declarations.filesystem.globalFootprintLimitBytes) {
                        add(Boundary.GLOBAL_FOOTPRINT)
                    }
                    if (demand.requiredFilesystemBytes > declarations.filesystem.capacityBytes) add(Boundary.FILESYSTEM)
                }
            if (exceeded.isEmpty()) Result.Fits(demand) else Result.Insufficient(demand, exceeded)
        } catch (_: ArithmeticException) {
            Result.Unproven
        }
    }

    private fun demand(d: Declarations): Demand {
        val payload = d.legacy.values.fold(ParticipantStorageShare.allPayloadBytes) { sum, share -> Math.addExact(sum, share.payloadBytes) }
        val footprint =
            d.legacy.values.fold(
                ParticipantStorageShare.allFootprintBytes,
            ) { sum, share -> Math.addExact(sum, share.footprintBytes) }
        val event = d.model.bound(d.worstEnvelope.bytes, d.worstEnvelope.objects)
        val globalPayload = Math.addExact(Math.addExact(payload, d.worstEnvelope.bytes), d.payloadFinalizeBudgetBytes)
        val globalFootprint = Math.addExact(Math.addExact(footprint, event), d.filesystem.finalizeBudgetBytes)
        val filesystem =
            listOf(
                d.unattributedDebtFootprintBytes,
                d.trashAllowanceBytes,
                d.filesystem.probeBudgetBytes,
                d.filesystem.metadataBudgetBytes,
                d.filesystem.operationalReserveBytes,
            ).fold(globalFootprint, Math::addExact)
        return Demand(payload, footprint, event, globalPayload, globalFootprint, filesystem)
    }
}
