package email.testinbox.application.storage

import email.testinbox.application.Sha256
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.CohortStorageBudget
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.ParticipantStorageShare
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.domain.storage.StorageUsage

/** Internal operator/T1 data. None of this global inventory is a tenant response. */
data class WorkspaceStorageBudget(
    val payloadBytes: Long,
    val footprintBytes: Long,
    val cleanup: FootprintAdmission.Load = FootprintAdmission.Load.ZERO,
) {
    init {
        require(payloadBytes > 0 && footprintBytes > 0)
    }

    fun charged(
        usage: StorageUsage,
        model: FootprintModel,
    ): Long {
        require(usage.committedBytes >= 0 && usage.reservedBytes >= 0) { "negative workspace accounting" }
        return model.bound(
            Math.addExact(usage.usedBytes, cleanup.bytes),
            Math.addExact(Math.addExact(usage.committedObjects, usage.reservedObjects), cleanup.objects),
        )
    }
}

data class CohortWorkspaceLoad(
    val budget: WorkspaceStorageBudget,
    val usage: StorageUsage,
    val participant: Boolean,
)

data class CohortPoolState(
    val hasParticipants: Boolean,
    val protected: Boolean,
    val binding: String?,
    val fingerprint: String?,
    val legacy: Map<WorkspaceId, LegacyStorageShare>,
    val unattributedAllowance: Long?,
    val trashAllowance: Long?,
    val unknownWorkspaces: Long,
    val nodesReady: Boolean,
    val unattributed: FootprintAdmission.Load,
    val trashBytes: Long?,
    val loads: Map<WorkspaceId, CohortWorkspaceLoad>,
) {
    val required: Boolean get() = hasParticipants || protected
}

/** A declaration-bound budget verifier shared by admission, pre-resolution health and T1. */
class CohortStorageRuntime(
    private val declarations: StorageDeclarations,
    val model: FootprintModel,
    private val limits: FootprintAdmission.Limits,
    private val worstEnvelope: FootprintAdmission.Load,
) {
    val binding: String? = makeBinding(declarations)

    fun fingerprint(
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
    ): String? {
        val prefix = binding ?: return null
        val shares =
            legacy.toSortedMap(compareBy { it.value.toString() }).flatMap { (id, share) ->
                listOf(id.value.toString(), share.payloadBytes.toString(), share.footprintBytes.toString())
            }
        return hash(listOf(prefix, unattributed.toString(), trash.toString()) + shares)
    }

    fun assess(
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
    ): CohortStorageBudget.Result {
        if (binding == null || legacy.size > MAX_LEGACY_WORKSPACES) return CohortStorageBudget.Result.Unproven
        val budget =
            CohortStorageBudget.Declarations(
                model,
                checkNotNull(declarations.globalLimitBytes),
                EffectiveStoragePolicy.finalizeBudget(declarations).bytes,
                limits,
                legacy,
                unattributed,
                trash,
                worstEnvelope,
            )
        return CohortStorageBudget.assess(budget)
    }

    fun unavailable(pool: CohortPoolState?): FootprintUnavailability? {
        if (pool == null || !pool.required) return null
        if (!approved(pool)) return FootprintUnavailability.COHORT_UNAPPROVED
        return try {
            val overallocated = pool.loads.values.any(::overallocated)
            val debtOver = model.bound(pool.unattributed.bytes, pool.unattributed.objects) > checkNotNull(pool.unattributedAllowance)
            val trashOver = pool.trashBytes == null || pool.trashBytes > checkNotNull(pool.trashAllowance)
            if (overallocated || debtOver || trashOver) FootprintUnavailability.COHORT_BUDGET_EXHAUSTED else null
        } catch (_: ArithmeticException) {
            FootprintUnavailability.INDETERMINATE
        } catch (_: IllegalArgumentException) {
            FootprintUnavailability.INDETERMINATE
        }
    }

    private fun approved(pool: CohortPoolState): Boolean {
        val debt = pool.unattributedAllowance ?: return false
        val trash = pool.trashAllowance ?: return false
        if (binding == null || pool.binding != binding) return false
        if (pool.fingerprint != fingerprint(pool.legacy, debt, trash)) return false
        if (assess(pool.legacy, debt, trash) !is CohortStorageBudget.Result.Fits) return false
        return pool.unknownWorkspaces == 0L && pool.nodesReady
    }

    private fun overallocated(load: CohortWorkspaceLoad): Boolean {
        val budget = load.budget
        if (load.participant && budget.payloadBytes != ParticipantStorageShare.PAYLOAD_BYTES) return true
        if (load.participant && budget.footprintBytes != ParticipantStorageShare.FOOTPRINT_BYTES) return true
        return load.usage.usedBytes > budget.payloadBytes || budget.charged(load.usage, model) > budget.footprintBytes
    }

    fun ready(pool: CohortPoolState?): Boolean {
        if (declarations.enforcement != StorageEnforcement.ALL) return false
        return pool?.binding != null && unavailable(pool) == null
    }

    companion object {
        const val VERSION = "cohort-storage-v1"
        const val MAX_LEGACY_WORKSPACES = 32

        private fun hash(parts: List<String>): String =
            Sha256.hex(parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))

        private fun makeBinding(d: StorageDeclarations): String? {
            if (d.globalLimitBytes == null || d.declaredMaxIngestionProcesses == null) return null
            if (d.expectedApiNodes.isEmpty() || d.expectedIngestionNodes.isEmpty()) {
                return null
            }
            val fs = d.filesystem
            val filesystem =
                listOf(
                    fs.blockSizeBytes,
                    fs.objectOverheadMaxBytes,
                    fs.globalFootprintLimitBytes,
                    fs.deletionDebtBudgetBytes,
                    fs.metadataBudgetBytes,
                    fs.operationalReserveBytes,
                    fs.capacityBytes,
                    fs.probeBudgetBytes,
                    fs.monitorRole,
                    fs.inodes,
                    fs.effectiveObservationMaxAge,
                )
            if (filesystem.any { it == null }) return null
            val values =
                listOf(
                    d.globalLimitBytes,
                    d.declaredBucketQuotaBytes,
                    d.declaredMaxIngestionProcesses,
                    d.maxConcurrentWrites,
                    d.maxObjectBytes,
                    d.uploadImplementationVersion,
                    d.inboxShare,
                ) + filesystem
            return hash(
                listOf(VERSION) + values.map { it?.toString() ?: "undeclared" } +
                    d.expectedApiNodes.sorted().map { "api:$it" } + d.expectedIngestionNodes.sorted().map { "ingestion:$it" } +
                    d.backendIdentity
                        ?.elements()
                        .orEmpty()
                        .toSortedMap()
                        .flatMap { (k, v) -> listOf(k, v ?: "undeclared") },
            )
        }
    }
}

fun interface CohortStorageReadiness {
    fun ready(): Boolean
}
