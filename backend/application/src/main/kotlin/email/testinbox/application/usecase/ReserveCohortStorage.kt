package email.testinbox.application.usecase

import email.testinbox.application.port.CohortBudgets
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FootprintGate
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.CohortStorageBudget
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageEnforcement

/** The sole invariant-bearing writer of pool approval. It NEVER opens admissions or clears a latch. */
class ReserveCohortStorage(
    private val store: CohortBudgets,
    private val tx: TransactionRunner,
    private val gate: FootprintGate,
    private val declarations: StorageDeclarations,
) {
    fun execute(
        legacy: Map<WorkspaceId, LegacyStorageShare>,
        unattributed: Long,
        trash: Long,
    ) {
        require(declarations.enforcement == StorageEnforcement.ALL) { "cohort approval requires ALL declarations" }
        val policy = checkNotNull(EffectiveStoragePolicy.footprint(declarations))
        val runtime = checkNotNull(policy.cohort)
        require(
            runtime.assess(legacy, unattributed, trash) is CohortStorageBudget.Result.Fits,
        ) { "all thirty shares and reserves must fit" }
        tx.required {
            store.lockClosed()
            store.reserve(
                declarations,
                checkNotNull(runtime.binding),
                checkNotNull(runtime.fingerprint(legacy, unattributed, trash)),
                legacy,
                unattributed,
                trash,
            )
            val observed = gate.observe()
            check(policy.unavailability(observed) == null && runtime.ready(observed?.cohort)) {
                "pool inventory, live charges, nodes or observation not ready"
            }
        }
    }

    fun revoke() =
        tx.required {
            store.lockClosed()
            store.revoke()
        }
}
