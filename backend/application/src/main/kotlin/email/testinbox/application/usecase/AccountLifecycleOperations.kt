package email.testinbox.application.usecase

import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountLifecycle
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState

/** The database role running the operator command is not the lifecycle operator authority. Nothing was changed. */
class OperatorAuthorityRequiredException :
    RuntimeException("the account-lifecycle operator database authority is required; nothing was changed")

/**
 * Runs [block] in ONE transaction whose first statement rechecks the operator
 * authority; the [OperatorAuthorization] it hands out is closed when [block]
 * returns, so it cannot outlive the transaction that verified it.
 */
private fun <T> TransactionRunner.asOperator(
    store: AccountLifecycleStore,
    block: (OperatorAuthorization) -> T,
): T =
    required {
        if (!store.operatorAuthorized()) throw OperatorAuthorityRequiredException()
        val authority = OperatorAuthorization()
        try {
            block(authority)
        } finally {
            authority.close()
        }
    }

/** Where an account's lifecycle transition stands after a command. */
enum class LifecyclePhase {
    /** The account is active (resumed or never suspended). */
    ACTIVE,

    /** Access is revoked, but reservations made before the fence are still charged: copies already in flight may finish. */
    DRAINING,

    /** Access is revoked and no reservation of the workspace remains: nothing can still be admitted or in flight. */
    SETTLED,
}

/**
 * The ONE account-state writer (ACCOUNT-LIFECYCLE.md): suspend, resume and
 * deletion-pending. Lock order, in one transaction: the existing workspace
 * admission guard, then the storage `(35, 1)` T1 fence, then the account row.
 * The state change, the permanent revocation of every browser session and
 * every managed key, and the reservation count all happen under those locks.
 *
 * Because T1 decides under the same `(35, 1)` fence and reads eligibility in
 * its one snapshot, a copy either reserved BEFORE this commit (it may finish
 * and is counted here as draining) or is decided after it and discarded as
 * inactive. No untracked admission can race the zero-reservation check.
 */
class ChangeAccountState(
    private val store: AccountLifecycleStore,
    private val guard: WorkspaceQuotaState,
    private val sessions: RevokeAccountSessions,
    private val keys: RevokeApiKey,
    private val tx: TransactionRunner,
) {
    data class Report(
        val accountId: HumanAccountId,
        val workspaceId: WorkspaceId?,
        val action: AccountLifecycleAction,
        val from: HumanAccountState,
        val to: HumanAccountState,
        val changed: Boolean,
        val revokedKeys: Int,
        val reservationsHeld: Long,
    ) {
        val phase: LifecyclePhase
            get() =
                when {
                    to == HumanAccountState.ACTIVE -> LifecyclePhase.ACTIVE
                    reservationsHeld > 0 -> LifecyclePhase.DRAINING
                    else -> LifecyclePhase.SETTLED
                }
    }

    sealed interface Result {
        data class Done(
            val report: Report,
        ) : Result

        data object AccountNotFound : Result

        data class Refused(
            val from: HumanAccountState,
            val reason: String,
        ) : Result
    }

    fun execute(
        accountId: HumanAccountId,
        action: AccountLifecycleAction,
    ): Result {
        repeat(MAX_ATTEMPTS) {
            attempt(accountId, action)?.let { return it }
        }
        error("the account's membership kept changing during $MAX_ATTEMPTS lifecycle attempts")
    }

    /** Null when the account was admitted between the unlocked read and the locks: retry with that workspace's guard. */
    private fun attempt(
        accountId: HumanAccountId,
        action: AccountLifecycleAction,
    ): Result? =
        tx.asOperator(store) { authority ->
            val workspace = store.findWorkspace(accountId)
            workspace?.let(guard::guardAdmission)
            store.lockStorageFence()
            val from = store.lockAccountState(accountId) ?: return@asOperator Result.AccountNotFound
            if (store.findWorkspace(accountId) != workspace) return@asOperator null
            if (action == AccountLifecycleAction.RESUME && store.quarantined()) {
                return@asOperator Result.Refused(from, "the database is restore-quarantined; run restore-reconcile first")
            }
            when (val decision = AccountLifecycle.decide(from, action)) {
                is AccountLifecycle.Decision.Refused -> {
                    Result.Refused(from, decision.reason)
                }

                else -> {
                    if (decision is AccountLifecycle.Decision.Change) store.setState(accountId, decision.to)
                    var revoked = 0
                    if (AccountLifecycle.revokesAccess(decision.to)) {
                        // Permanent: resuming changes only the state, never these rows.
                        sessions.execute(accountId)
                        revoked = workspace?.let { keys.revokeWorkspaceForLifecycle(authority, it) } ?: 0
                    }
                    Result.Done(
                        Report(
                            accountId = accountId,
                            workspaceId = workspace,
                            action = action,
                            from = from,
                            to = decision.to,
                            changed = decision is AccountLifecycle.Decision.Change,
                            revokedKeys = revoked,
                            reservationsHeld = workspace?.let(store::reservationsHeld) ?: 0,
                        ),
                    )
                }
            }
        }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * The content step of account deletion: once the workspace has drained, every
 * live inbox goes through the EXISTING [DeleteInbox] path (state DELETED,
 * EXACT cooldown), and the existing retention sweep removes rows and objects
 * exactly as for any deleted inbox. Physical cleanup stays charged to the
 * participant's share until proven (V20); the spent place and the durable
 * participant marker are never released.
 */
class RemoveAccountContent(
    private val store: AccountLifecycleStore,
    private val deleteInbox: DeleteInbox,
    private val tx: TransactionRunner,
) {
    enum class Phase {
        /** Reservations from before the fence are still charged; nothing is deleted yet. Re-run later. */
        DRAINING,

        /** Every inbox is DELETED; the retention sweep has not yet removed all of their rows and objects. Re-run later. */
        CONTENT_PENDING,

        /** No inbox row and no reservation of the workspace remain. */
        CONTENT_REMOVED,
    }

    sealed interface Result {
        data class Done(
            val workspaceId: WorkspaceId?,
            val phase: Phase,
            val inboxesMarkedDeleted: Int,
            val inboxRowsRemaining: Long,
        ) : Result

        data object AccountNotFound : Result

        data class NotDeletionPending(
            val state: HumanAccountState,
        ) : Result
    }

    fun execute(accountId: HumanAccountId): Result {
        val workspace =
            when (val start = tx.asOperator(store) { begin(accountId) }) {
                is Start.Finished -> return start.result
                is Start.Proceed -> start.workspace
            }
        var marked = 0
        repeat(MAX_BATCHES) {
            val batch = tx.asOperator(store) { store.undeletedInboxes(workspace, BATCH) }
            if (batch.isEmpty()) return finish(workspace, marked)
            batch.forEach { inbox -> if (deleteInbox.execute(workspace, inbox) == DeleteInbox.Result.Deleted) marked++ }
        }
        return finish(workspace, marked)
    }

    private sealed interface Start {
        data class Finished(
            val result: Result,
        ) : Start

        data class Proceed(
            val workspace: WorkspaceId,
        ) : Start
    }

    private fun begin(accountId: HumanAccountId): Start {
        val state = store.lockAccountState(accountId) ?: return Start.Finished(Result.AccountNotFound)
        if (state != HumanAccountState.DELETION_PENDING) return Start.Finished(Result.NotDeletionPending(state))
        val workspace = store.findWorkspace(accountId) ?: return Start.Finished(Result.Done(null, Phase.CONTENT_REMOVED, 0, 0))
        if (store.reservationsHeld(workspace) > 0) {
            return Start.Finished(Result.Done(workspace, Phase.DRAINING, 0, store.inboxRows(workspace)))
        }
        return Start.Proceed(workspace)
    }

    private fun finish(
        workspace: WorkspaceId,
        marked: Int,
    ): Result {
        val remaining = tx.asOperator(store) { store.inboxRows(workspace) }
        return Result.Done(workspace, if (remaining > 0) Phase.CONTENT_PENDING else Phase.CONTENT_REMOVED, marked, remaining)
    }

    private companion object {
        const val BATCH = 100

        /** Far above the active-inbox quota; a bound so a misbehaving repository cannot loop forever. */
        const val MAX_BATCHES = 1_000
    }
}

/**
 * Erases a deletion-pending account's identity rows after its content is
 * gone: the account row, and through its existing cascades every login
 * identity and session; the membership keeps its spent place but loses its
 * account and workspace references. The durable participant marker and any
 * pending cleanup charge remain, so the workspace stays an INACTIVE
 * participant, never a legacy one.
 *
 * WHEN to run this is an OPEN OWNER DECISION (LIFECYCLE.md): no accepted
 * retention period for contact/identity data after deletion exists yet, so
 * the delete procedure itself stops before this step.
 */
class EraseAccountIdentity(
    private val store: AccountLifecycleStore,
    private val guard: WorkspaceQuotaState,
    private val sessions: RevokeAccountSessions,
    private val keys: RevokeApiKey,
    private val tx: TransactionRunner,
) {
    sealed interface Result {
        data class Erased(
            val workspaceId: WorkspaceId?,
        ) : Result

        data object AccountNotFound : Result

        data class NotDeletionPending(
            val state: HumanAccountState,
        ) : Result

        data class ContentRemains(
            val reservationsHeld: Long,
            val inboxRows: Long,
        ) : Result
    }

    fun execute(accountId: HumanAccountId): Result =
        tx.asOperator(store) { authority ->
            val workspace = store.findWorkspace(accountId)
            workspace?.let(guard::guardAdmission)
            store.lockStorageFence()
            val state = store.lockAccountState(accountId) ?: return@asOperator Result.AccountNotFound
            if (state != HumanAccountState.DELETION_PENDING) return@asOperator Result.NotDeletionPending(state)
            // A deletion-pending account cannot be admitted, so its membership cannot change under these locks.
            check(store.findWorkspace(accountId) == workspace) { "membership changed under the lifecycle locks" }
            if (workspace != null) {
                val held = store.reservationsHeld(workspace)
                val rows = store.inboxRows(workspace)
                if (held > 0 || rows > 0) return@asOperator Result.ContentRemains(held, rows)
                keys.revokeWorkspaceForLifecycle(authority, workspace)
            }
            sessions.execute(accountId)
            store.eraseIdentity(accountId, workspace)
            Result.Erased(workspace)
        }
}

/**
 * The restore quarantine exit (ACCOUNT-LIFECYCLE.md: "a backup is never
 * current authorization"). Allowed only while the database carries no
 * clearance, i.e. after a restore or an upgrade that found participants.
 * In one transaction it closes admissions and capacity, suspends every
 * restored ACTIVE participant account, removes their sessions, permanently
 * revokes every managed key of every participant workspace and only then
 * records the clearance. Accounts the operator's external lifecycle record
 * shows as active are resumed individually afterwards; they sign in again and
 * issue new keys. Deletions recorded after the backup are re-run.
 */
class ReconcileRestoredDatabase(
    private val store: AccountLifecycleStore,
    private val guard: WorkspaceQuotaState,
    private val sessions: RevokeAccountSessions,
    private val keys: RevokeApiKey,
    private val tx: TransactionRunner,
) {
    sealed interface Result {
        data class Reconciled(
            val participantWorkspaces: Int,
            val suspendedAccounts: Int,
            val revokedKeys: Int,
        ) : Result

        data object NotQuarantined : Result
    }

    fun execute(): Result =
        tx.asOperator(store) { authority ->
            if (!store.quarantined()) return@asOperator Result.NotQuarantined
            val participants = store.participants().sortedBy { it.workspaceId.value }
            // The documented order: every workspace guard (sorted, so two multi-guard
            // holders cannot cross), the cohort row and the T1 fence, then the accounts.
            participants.forEach { guard.guardAdmission(it.workspaceId) }
            store.lockCohortAndFence()
            // Rechecked under the locks: a concurrent reconciliation that committed
            // while this one waited makes this run a clean no-op (it rolls nothing back
            // because it has written nothing yet).
            if (!store.quarantined()) return@asOperator Result.NotQuarantined
            store.closeCohort()
            var suspended = 0
            participants.mapNotNull { it.accountId }.distinct().forEach { account ->
                if (store.lockAccountState(account) == HumanAccountState.ACTIVE) {
                    store.setState(account, HumanAccountState.SUSPENDED)
                    suspended++
                }
                sessions.execute(account)
            }
            val revoked = participants.sumOf { keys.revokeWorkspaceForLifecycle(authority, it.workspaceId) }
            store.clearQuarantine()
            Result.Reconciled(participants.size, suspended, revoked)
        }
}

/** Read-only: what an operator needs to decide the next step. No contact data. */
class AccountLifecycleStatus(
    private val store: AccountLifecycleStore,
    private val tx: TransactionRunner,
) {
    data class Status(
        val accountId: HumanAccountId,
        val state: HumanAccountState,
        val workspaceId: WorkspaceId?,
        val reservationsHeld: Long,
        val inboxRows: Long,
        val quarantined: Boolean,
    ) {
        val phase: LifecyclePhase
            get() =
                when {
                    state == HumanAccountState.ACTIVE -> LifecyclePhase.ACTIVE
                    reservationsHeld > 0 -> LifecyclePhase.DRAINING
                    else -> LifecyclePhase.SETTLED
                }
    }

    fun execute(accountId: HumanAccountId): Status? =
        tx.asOperator(store) {
            val state = store.lockAccountState(accountId) ?: return@asOperator null
            val workspace = store.findWorkspace(accountId)
            Status(
                accountId,
                state,
                workspace,
                workspace?.let(store::reservationsHeld) ?: 0,
                workspace?.let(store::inboxRows) ?: 0,
                store.quarantined(),
            )
        }
}
