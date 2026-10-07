package email.testinbox.application.storage

import email.testinbox.application.LimitsConfig
import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy

/**
 * The ONE effective ADR-035 storage policy of a deployment (§3), derived from
 * the ADR-027 limits: the workspace limit is the existing `max-stored-bytes`,
 * and the inbox share, *G* and *H* are the ADR-035 reference values.
 *
 * It is shared, by construction, between the two places that read a ceiling:
 * T1 admission in the ingestion gateway (observational while enforcement is
 * OFF) and the authenticated visibility API (`StorageUsage.limitBytes`,
 * TI-STORAGE-004). Two formulas could drift apart and show a tenant a limit
 * that admission does not apply; one factory cannot. `EffectiveStoragePolicyTest`
 * asserts the equality.
 *
 * This decides limits only. Whether any of them refuses is a separate
 * `StorageEnforcement` value, and the live wiring passes `OFF` as a literal.
 */
object EffectiveStoragePolicy {
    fun of(limits: LimitsConfig): StorageCapacityPolicy {
        val reference = StorageCapacityPolicy.ADR_035_REFERENCE
        val workspace = limits.quotas.maxStoredBytes.coerceAtMost(reference.globalLimitBytes)
        // A workspace limit too small for the reference share to floor above
        // zero (only ever a test setting) observes against the whole workspace.
        val share = if (reference.inboxShare.floorOf(workspace) > 0) reference.inboxShare else InboxShare.of("1")
        return StorageCapacityPolicy(workspace, share, reference.globalLimitBytes, reference.finalizeBudgetBytes)
    }
}
