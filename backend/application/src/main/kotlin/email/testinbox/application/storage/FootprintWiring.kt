package email.testinbox.application.storage

import email.testinbox.application.port.InboxTeardown
import email.testinbox.application.port.RowFreeDebtStore
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageLedger
import email.testinbox.application.usecase.PacedTeardown
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageScope
import java.time.Clock

/**
 * Every TI-STORAGE-006E PR D wiring decision, from the validated declarations,
 * in ONE place both deployables call and a unit test pins (the quality review's
 * finding: a wiring line that passes `null` was invisible to every test).
 *
 * The rule throughout: the footprint machinery exists only where the deployment
 * declares its filesystem; it ENFORCES only under `ALL`; under `TENANT_LIMITS`
 * it observes and records; with no declared filesystem nothing changes.
 */
object FootprintWiring {
    private fun footprint(declarations: StorageDeclarations) = EffectiveStoragePolicy.footprint(declarations)

    private fun enforced(declarations: StorageDeclarations) =
        declarations.enforcement.enforces(StorageScope.GLOBAL) && footprint(declarations) != null

    /** Whether T1's statement reads the V8 footprint tables (an undeclared OFF deployment's roles may not hold them). */
    fun readsFootprint(declarations: StorageDeclarations): Boolean = footprint(declarations) != null

    /** T1. The one construction site the architecture rule allows. */
    fun admission(
        store: StorageAdmissionStore,
        policy: StorageCapacityPolicy,
        declarations: StorageDeclarations,
    ): StorageAdmission = StorageAdmission(store, policy, declarations.enforcement, footprint = footprint(declarations))

    /** The check before recipient resolution. */
    fun precheck(
        declarations: StorageDeclarations,
        gate: FootprintGate,
    ): FootprintPrecheck = FootprintPrecheck(footprint(declarations), declarations.enforcement, gate)

    /** Lemma 3's orphaned-row count for [WriteSlots], or null where only per-node slots apply. */
    fun orphanedSlots(
        declarations: StorageDeclarations,
        count: () -> Int,
    ): (() -> Int)? = if (enforced(declarations)) count else null

    /** Rule (P) for every row-free deletion. */
    fun rowFreeDebt(
        store: RowFreeDebtStore,
        declarations: StorageDeclarations,
    ): RowFreeDebt = RowFreeDebt(store, footprint(declarations), declarations.enforcement)

    /** Paced retention, or null for today's whole-inbox teardown. */
    fun pacedTeardown(
        declarations: StorageDeclarations,
        ledger: StorageLedger,
        clock: Clock,
        teardown: InboxTeardown?,
    ): PacedTeardown? {
        val pacing = EffectiveStoragePolicy.retentionPacing(declarations, ledger, clock)
        if (pacing === RetentionPacing.UNPACED || teardown == null) return null
        return PacedTeardown(pacing, teardown)
    }

    /** The prompt reconciliation on distrust, where a filesystem is declared. */
    fun onUntrusted(
        declarations: StorageDeclarations,
        reconcile: () -> Unit,
    ): (() -> Unit)? = if (footprint(declarations) != null) reconcile else null
}
