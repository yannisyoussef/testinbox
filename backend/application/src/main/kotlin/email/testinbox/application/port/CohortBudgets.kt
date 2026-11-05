package email.testinbox.application.port

import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.CohortStorageBudget
import email.testinbox.domain.storage.LegacyStorageShare

/** Operator-only writes. Runtime credentials have SELECT only. Retired allocations are never recycled. */
interface CohortBudgets {
    /** Cohort row, then global T1 advisory lock. Refuses open admissions/capacity. */
    fun lockClosed()

    /**
     * Persisted shares may only stay equal or increase; no implicit retirement.
     * [partition] is the bound these declarations leave for all shares, recorded
     * beside the plan for the same binding and fingerprint (V25): PostgreSQL then
     * refuses share growth past it and the share-partitioned T1 re-checks it.
     */
    @Suppress("LongParameterList") // one parameter per recorded fact of the approved pool
    fun reserve(
        declarations: StorageDeclarations,
        binding: String,
        fingerprint: String,
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
        partition: CohortStorageBudget.PartitionCapacity,
    )

    /** Keep the plan and all shares, invalidate approval and retain the node fence. */
    fun revoke()
}
