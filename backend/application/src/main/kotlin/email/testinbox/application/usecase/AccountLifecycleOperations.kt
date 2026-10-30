package email.testinbox.application.usecase

import email.testinbox.application.port.AccountDeletionRecord
import email.testinbox.application.port.AccountLifecycleStore
import email.testinbox.application.port.IdentityErasure
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountContentRemaining
import email.testinbox.domain.identity.AccountDeletionPolicy
import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.AccountLifecycle
import email.testinbox.domain.identity.AccountLifecycleAction
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import java.time.Clock
import java.time.Instant

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
    private val clock: Clock,
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
                    // The deletion workflow's record starts in the same transaction as the state it follows.
                    if (decision.to == HumanAccountState.DELETION_PENDING) store.openDeletion(accountId, clock.instant())
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
 * The account deletion workflow (TI-DELIVERY-002b owner decision 1): one
 * resumable, idempotent advance per call, through explicit stages recorded in
 * `account_deletion` (V23):
 *
 * 1. **DRAINING** — access is revoked ([ChangeAccountState]); reservations made
 *    before the fence may still finish. Nothing is deleted while any remain.
 * 2. **CONTENT_DELETING** — every live inbox goes through the EXISTING
 *    [DeleteInbox] path (state DELETED, EXACT cooldown), and the existing
 *    retention sweep removes rows and objects (objects proven deleted first)
 *    exactly as for any deleted inbox. The stage ends when no inbox row, no
 *    reservation and no live EXACT cooldown of the workspace remains.
 * 3. **ERASED** — in one transaction under the lifecycle locks, the content
 *    gate is re-read and only then is the identity erased
 *    ([AccountLifecycleStore.eraseIdentity]). The spent place, the participant
 *    marker, every cleanup charge and the revoked keys' technical rows remain.
 *
 * Every stage is re-entrant: a run that dies between two stages (or inside the
 * per-inbox loop) leaves a state the next run continues from, and a run after
 * ERASED reports ERASED without touching anything. The operator timer calls
 * [execute] for every pending deletion ([pending]).
 */
class ProcessAccountDeletion(
    private val store: AccountLifecycleStore,
    private val guard: WorkspaceQuotaState,
    private val sessions: RevokeAccountSessions,
    private val keys: RevokeApiKey,
    private val deleteInbox: DeleteInbox,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    data class Progress(
        val accountId: HumanAccountId,
        val workspaceId: WorkspaceId?,
        val stage: AccountDeletionStage,
        val remaining: AccountContentRemaining,
        val requestedAt: Instant?,
        val inboxesMarkedDeleted: Int = 0,
        /** Set only by the run that performed the erasure. */
        val erasure: IdentityErasure? = null,
        /** The record was pending but the account row was already gone (removed outside this workflow). */
        val accountRowAbsent: Boolean = false,
    )

    sealed interface Result {
        data class Advanced(
            val progress: Progress,
        ) : Result

        data object AccountNotFound : Result

        data class NotDeletionPending(
            val state: HumanAccountState,
        ) : Result
    }

    /**
     * The deletions the timer should advance: every unfinished record, after
     * adopting any DELETION_PENDING account that has none (a database restored
     * or edited outside this workflow), so no deletion is silently forgotten.
     */
    fun pending(): List<AccountDeletionRecord> =
        tx.asOperator(store) {
            val now = clock.instant()
            store.unrecordedDeletions().forEach { store.openDeletion(it, now) }
            store.pendingDeletions()
        }

    fun execute(accountId: HumanAccountId): Result {
        val workspace =
            when (val start = tx.asOperator(store) { begin(accountId) }) {
                is Start.Finished -> return start.result
                is Start.Proceed -> start.workspace
            }
        val marked = workspace?.let(::markInboxesDeleted) ?: 0
        return tx.asOperator(store) { authority -> eraseWhenComplete(accountId, workspace, marked, authority) }
    }

    private sealed interface Start {
        data class Finished(
            val result: Result,
        ) : Start

        data class Proceed(
            val workspace: WorkspaceId?,
        ) : Start
    }

    private fun begin(accountId: HumanAccountId): Start {
        val now = clock.instant()
        val record = store.deletion(accountId)
        val state = store.lockAccountState(accountId) ?: return Start.Finished(absent(accountId, record, now))
        if (state != HumanAccountState.DELETION_PENDING) return Start.Finished(Result.NotDeletionPending(state))
        if (record == null) store.openDeletion(accountId, now)
        val workspace = store.findWorkspace(accountId)
        if (workspace != null) {
            val held = store.reservationsHeld(workspace)
            if (held > 0) {
                val remaining = AccountContentRemaining(held, store.inboxRows(workspace), store.exactCooldowns(workspace, now))
                return Start.Finished(advanced(accountId, workspace, AccountDeletionStage.DRAINING, remaining))
            }
        }
        store.advanceDeletion(accountId, AccountDeletionStage.CONTENT_DELETING, now)
        return Start.Proceed(workspace)
    }

    /** No account row: never deleted (not found), already ERASED, or removed outside the workflow (closed now). */
    private fun absent(
        accountId: HumanAccountId,
        record: AccountDeletionRecord?,
        now: Instant,
    ): Result {
        record ?: return Result.AccountNotFound
        val rowAbsent = record.stage != AccountDeletionStage.ERASED
        // The login identities, sessions, waitlist entry and budget cascaded with the row.
        if (rowAbsent) store.advanceDeletion(accountId, AccountDeletionStage.ERASED, now)
        return Result.Advanced(
            Progress(
                accountId,
                null,
                AccountDeletionStage.ERASED,
                AccountContentRemaining.NONE,
                record.requestedAt,
                accountRowAbsent = rowAbsent,
            ),
        )
    }

    private fun markInboxesDeleted(workspace: WorkspaceId): Int {
        var marked = 0
        repeat(MAX_BATCHES) {
            val batch = tx.asOperator(store) { store.undeletedInboxes(workspace, BATCH) }
            if (batch.isEmpty()) return marked
            batch.forEach { inbox -> if (deleteInbox.execute(workspace, inbox) == DeleteInbox.Result.Deleted) marked++ }
        }
        return marked
    }

    /** The lifecycle lock order, then the content gate re-read under it; erasure only when nothing remains. */
    private fun eraseWhenComplete(
        accountId: HumanAccountId,
        workspace: WorkspaceId?,
        marked: Int,
        authority: OperatorAuthorization,
    ): Result {
        val now = clock.instant()
        workspace?.let(guard::guardAdmission)
        store.lockStorageFence()
        val state = store.lockAccountState(accountId) ?: return absent(accountId, store.deletion(accountId), now)
        if (state != HumanAccountState.DELETION_PENDING) return Result.NotDeletionPending(state)
        // A deletion-pending account cannot be admitted, so its membership cannot change under these locks.
        check(store.findWorkspace(accountId) == workspace) { "membership changed under the lifecycle locks" }
        val remaining =
            workspace?.let {
                AccountContentRemaining(store.reservationsHeld(it), store.inboxRows(it), store.exactCooldowns(it, now))
            } ?: AccountContentRemaining.NONE
        if (!remaining.none) {
            store.advanceDeletion(accountId, remaining.stage, now)
            return advanced(accountId, workspace, remaining.stage, remaining, marked)
        }
        // Already revoked when the deletion began; repeated so erasure can never leave a usable credential.
        workspace?.let { keys.revokeWorkspaceForLifecycle(authority, it) }
        sessions.execute(accountId)
        val erasure = store.eraseIdentity(accountId, workspace, now)
        return advanced(accountId, workspace, AccountDeletionStage.ERASED, remaining, marked, erasure)
    }

    private fun advanced(
        accountId: HumanAccountId,
        workspace: WorkspaceId?,
        stage: AccountDeletionStage,
        remaining: AccountContentRemaining,
        marked: Int = 0,
        erasure: IdentityErasure? = null,
    ): Result =
        Result.Advanced(
            Progress(accountId, workspace, stage, remaining, store.deletion(accountId)?.requestedAt, marked, erasure),
        )

    private companion object {
        const val BATCH = 100

        /** Far above the active-inbox quota; a bound so a misbehaving repository cannot loop forever. */
        const val MAX_BATCHES = 1_000
    }
}

/**
 * The restore quarantine exit (ACCOUNT-LIFECYCLE.md: "a backup is never
 * current authorization"). Allowed only while the database carries no
 * clearance, i.e. after a restore or an upgrade that found participants.
 * In one transaction it closes admissions and capacity, suspends every
 * restored ACTIVE participant account, removes their sessions, permanently
 * revokes every managed key of EVERY workspace (participant, owner and
 * monitoring alike: TI-DELIVERY-002b decision 2) and only then records the
 * clearance. Ops then issues replacement credentials through the bootstrap
 * break-glass, which reopens exactly because no usable administrator is left. Accounts the operator's external lifecycle record
 * shows as active are resumed individually afterwards; they sign in again and
 * issue new keys. Deletions recorded after the backup are re-run.
 */
class ReconcileRestoredDatabase(
    private val store: AccountLifecycleStore,
    private val guard: WorkspaceQuotaState,
    private val sessions: RevokeAccountSessions,
    private val keys: RevokeApiKey,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    sealed interface Result {
        data class Reconciled(
            val participantWorkspaces: Int,
            val suspendedAccounts: Int,
            val revokedKeys: Int,
            /** Owner and monitoring (non-participant) managed keys restored from the backup and revoked here. */
            val legacyRevokedKeys: Int = 0,
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
            // TI-DELIVERY-002b decision 2: no restored managed credential survives, legacy ones
            // included. Bootstrap credentials are configuration, not restored rows: not touched.
            val legacy = store.revokeEveryManagedKey(clock.instant())
            store.clearQuarantine()
            Result.Reconciled(participants.size, suspended, revoked, legacy)
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
        val deletion: AccountDeletionRecord? = null,
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
                store.deletion(accountId),
            )
        }

    /** For an account with no row: its deletion record, which says whether (and when) it was ERASED. */
    fun deletionRecord(accountId: HumanAccountId): AccountDeletionRecord? = tx.asOperator(store) { store.deletion(accountId) }
}

/**
 * Read-only: every unfinished deletion with its live content figures and
 * whether it is overdue under [policy]. What `account-lifecycle status`
 * prints, and what an Ops alert keys on. No contact data.
 */
class AccountDeletionReport(
    private val store: AccountLifecycleStore,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val policy: AccountDeletionPolicy,
) {
    data class Entry(
        val record: AccountDeletionRecord,
        val workspaceId: WorkspaceId?,
        val remaining: AccountContentRemaining,
        val age: java.time.Duration,
        val overdue: Boolean,
    )

    data class Report(
        val entries: List<Entry>,
        /** DELETION_PENDING accounts with no record: anomalous, so always counted as overdue until adopted. */
        val unrecorded: List<HumanAccountId>,
    ) {
        val overdue: Int get() = entries.count { it.overdue } + unrecorded.size
    }

    fun execute(): Report =
        tx.asOperator(store) {
            val now = clock.instant()
            val entries =
                store.pendingDeletions().map { record ->
                    val workspace = store.findWorkspace(record.accountId)
                    val remaining =
                        workspace?.let {
                            AccountContentRemaining(store.reservationsHeld(it), store.inboxRows(it), store.exactCooldowns(it, now))
                        } ?: AccountContentRemaining.NONE
                    Entry(
                        record,
                        workspace,
                        remaining,
                        java.time.Duration.between(record.requestedAt, now),
                        policy.overdue(record.stage, record.requestedAt, now),
                    )
                }
            Report(entries, store.unrecordedDeletions())
        }

    fun overdue(record: AccountDeletionRecord): Boolean = policy.overdue(record.stage, record.requestedAt, clock.instant())
}
