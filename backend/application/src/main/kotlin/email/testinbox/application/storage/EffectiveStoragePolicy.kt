package email.testinbox.application.storage

import email.testinbox.application.LimitsConfig
import email.testinbox.domain.storage.FinalizeBudget
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy

/**
 * The ONE effective ADR-035 storage policy of a deployment (§3), derived from
 * the ADR-027 limits and the deployment's storage declarations: the
 * workspace limit is the existing `max-stored-bytes`; the inbox share, *G*
 * and the declared process count behind *H* are the deployment's own
 * declarations (§18 prerequisite 10), with the ADR-035 reference values as
 * the OFF defaults (§14 Phase 2).
 *
 * It is shared, by construction, between the two places that read a ceiling:
 * T1 admission in the ingestion gateway and the authenticated visibility API
 * (`StorageUsage.limitBytes`, TI-STORAGE-004). Two formulas could drift apart
 * and show a tenant a limit that admission does not apply; one factory
 * cannot. `EffectiveStoragePolicyTest` asserts the equality, and an ArchUnit
 * rule keeps every other constructor call out.
 *
 * This decides limits only. Whether any of them refuses is the
 * `StorageEnforcement` value of the same declarations, validated by
 * `DeploymentSafety` before any node starts.
 */
object EffectiveStoragePolicy {
    fun of(
        limits: LimitsConfig,
        declarations: StorageDeclarations = StorageDeclarations.OFF,
    ): StorageCapacityPolicy {
        val reference = StorageCapacityPolicy.ADR_035_REFERENCE
        val globalLimit = declarations.globalLimitBytes ?: reference.globalLimitBytes
        val workspace = limits.quotas.maxStoredBytes.coerceAtMost(globalLimit)
        val declaredShare = declarations.inboxShare?.let(InboxShare::of) ?: reference.inboxShare
        // A workspace limit too small for the share to floor above zero (only
        // ever a test setting) observes against the whole workspace.
        val share = if (declaredShare.floorOf(workspace) > 0) declaredShare else InboxShare.of("1")
        return StorageCapacityPolicy(workspace, share, globalLimit, finalizeBudget(declarations).bytes)
    }

    /**
     * The global footprint rules of the filesystem-containment contract, or
     * null while the filesystem is not fully declared (an `OFF` deployment;
     * `DeploymentSafety` refuses a non-`OFF` one that is not). *H_F* is
     * `procs × 16 × F(maxObject, 1)`, in footprint (contract §1).
     */
    fun footprint(declarations: StorageDeclarations): FootprintPolicy? {
        val fs = declarations.filesystem
        val block = fs.blockSizeBytes ?: return null
        val overhead = fs.objectOverheadMaxBytes ?: return null
        val model = FootprintModel(block, overhead)
        val budget = finalizeBudget(declarations)
        return FootprintPolicy(
            model = model,
            limits =
                FootprintAdmission.Limits(
                    globalFootprintLimitBytes = fs.globalFootprintLimitBytes ?: return null,
                    finalizeBudgetBytes =
                        model.finalizeBudgetBytes(
                            budget.declaredMaxIngestionProcesses,
                            budget.maxConcurrentWrites,
                            budget.maxObjectBytes,
                        ),
                    metadataBudgetBytes = fs.metadataBudgetBytes ?: return null,
                    operationalReserveBytes = fs.operationalReserveBytes ?: return null,
                    capacityBytes = fs.capacityBytes ?: return null,
                    probeBudgetBytes = fs.probeBudgetBytes ?: return null,
                ),
            monitorRole = fs.monitorRole ?: return null,
        )
    }

    /**
     * `H = declaredMaxIngestionProcesses × maxConcurrentWrites × maxObjectBytes`
     * (ADR-035 §9), checked. The reference process count (1) stands in only
     * while enforcement is OFF; `DeploymentSafety` refuses a non-OFF
     * deployment that has not declared it, deploy surge included.
     */
    fun finalizeBudget(declarations: StorageDeclarations): FinalizeBudget =
        FinalizeBudget(
            declaredMaxIngestionProcesses =
                declarations.declaredMaxIngestionProcesses
                    ?: StorageCapacityPolicy.REFERENCE_FINALIZE_BUDGET.declaredMaxIngestionProcesses,
            maxConcurrentWrites = declarations.maxConcurrentWrites,
            maxObjectBytes = declarations.maxObjectBytes,
        )
}
