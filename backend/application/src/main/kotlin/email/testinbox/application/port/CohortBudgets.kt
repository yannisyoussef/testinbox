package email.testinbox.application.port

import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare

/** Operator-only writes. Runtime credentials have SELECT only. Retired allocations are never recycled. */
interface CohortBudgets {
    /** Cohort row, then global T1 advisory lock. Refuses open admissions/capacity. */
    fun lockClosed()

    /** Persisted shares may only stay equal or increase; no implicit retirement. */
    fun reserve(
        declarations: StorageDeclarations,
        binding: String,
        fingerprint: String,
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
    )

    /** Keep the plan and all shares, invalidate approval and retain the node fence. */
    fun revoke()
}
