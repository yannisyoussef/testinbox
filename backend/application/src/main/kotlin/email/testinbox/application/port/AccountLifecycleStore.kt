package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.AccountDeletionStage
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanAccountState
import java.time.Instant

/**
 * Whether a workspace may currently act: authenticate API keys, create
 * inboxes or keys, and receive new mail (ACCOUNT-LIFECYCLE.md). Derived on
 * every call from the durable participant marker, the membership and the
 * live verified account; nothing is cached.
 */
fun interface WorkspaceEligibility {
    fun status(workspaceId: WorkspaceId): WorkspaceStatus

    fun isActive(workspaceId: WorkspaceId): Boolean = status(workspaceId) != WorkspaceStatus.INACTIVE_PARTICIPANT
}

enum class WorkspaceStatus {
    /** No participant marker: an owner/monitoring workspace, unaffected by account lifecycle. */
    LEGACY,
    ACTIVE_PARTICIPANT,

    /** Suspended, deletion-pending, erased, missing membership, or the database is restore-quarantined. */
    INACTIVE_PARTICIPANT,
}

/** Thrown when a write discovers, under its workspace guard, that the workspace became inactive after authentication. */
class WorkspaceInactiveException : RuntimeException("the workspace is no longer active")

/**
 * Whether the database is restore-quarantined (V22: it carries no restore
 * clearance). Read per request by API-key authentication, so that NO restored
 * managed credential, participant or legacy, authenticates before the
 * operator's `restore-reconcile` has revoked it (TI-DELIVERY-002b decision 2).
 */
fun interface RestoreQuarantine {
    fun active(): Boolean

    companion object {
        /** For contexts with no lifecycle schema (unit tests of unrelated use cases). */
        val NONE = RestoreQuarantine { false }
    }
}

/** A deletion's durable, non-identifying record (V23): the account UUID, its stage and when. */
data class AccountDeletionRecord(
    val accountId: HumanAccountId,
    val stage: AccountDeletionStage,
    val requestedAt: Instant,
    val stageChangedAt: Instant,
    val erasedAt: Instant?,
)

/** What one identity erasure removed or anonymized, as counts for the operator's metadata line. */
data class IdentityErasure(
    /** Free-text names of the workspace's keys (revoked, retained for audit) set to null. */
    val anonymizedKeyLabels: Int,
    /** Sign-in codes issued to the account's contact address. */
    val loginChallenges: Int,
    /** The workspace's idempotency records: request fingerprints and projections that may name keys or addresses. */
    val idempotencyRecords: Int,
    /** The workspace's EXACT address reservations, all released by then: the chosen local parts. */
    val addressReservations: Int,
)

/** A participant's membership as the lifecycle writer sees it. No contact data. */
data class LifecycleMembership(
    val accountId: HumanAccountId?,
    val workspaceId: WorkspaceId,
)

/**
 * The operator lifecycle adapter. Not a runtime bean: only the offline
 * `account-lifecycle` command composes it, with operator database
 * credentials. Every mutation runs inside the caller's transaction, after
 * the lock order of [email.testinbox.application.usecase.ChangeAccountState]:
 * workspace admission guard, then the storage T1 fence, then the account row.
 */
@Suppress("TooManyFunctions") // one statement per step of the documented procedure
interface AccountLifecycleStore {
    /** Rechecked in the write transaction: the session's database role holds the operator authority. */
    fun operatorAuthorized(): Boolean

    /** The account's admitted workspace, read without a lock (the caller rechecks it under the locks). */
    fun findWorkspace(accountId: HumanAccountId): WorkspaceId?

    /** The existing ADR-035 global admission lock `(35, 1)`: the same fence T1 decides under. */
    fun lockStorageFence()

    /** The raw stored state, row-locked against sign-in and other state writers; null when there is no such account. */
    fun lockAccountState(accountId: HumanAccountId): HumanAccountState?

    fun setState(
        accountId: HumanAccountId,
        state: HumanAccountState,
    )

    /** Every reservation still charged to the workspace, whatever its state. Zero means nothing is in flight. */
    fun reservationsHeld(workspaceId: WorkspaceId): Long

    /** Inbox rows of every state still present (DELETED/EXPIRED rows remain until the retention sweep removes them). */
    fun inboxRows(workspaceId: WorkspaceId): Long

    /** EXACT reservations of the workspace still ACTIVE, or in COOLDOWN with `available_at` after [now]. */
    fun exactCooldowns(
        workspaceId: WorkspaceId,
        now: Instant,
    ): Long

    /** Inboxes not yet DELETED, at most [limit]. */
    fun undeletedInboxes(
        workspaceId: WorkspaceId,
        limit: Int,
    ): List<InboxId>

    /** True while the database carries no restore clearance. */
    fun quarantined(): Boolean

    /** Every participant marker with its current membership (accountId null once the membership was cleared). */
    fun participants(): List<LifecycleMembership>

    /** Locks the cohort row, then takes the T1 fence (the admission lock order). */
    fun lockCohortAndFence()

    /** Closes admissions and capacity on the locked cohort row. */
    fun closeCohort()

    fun clearQuarantine()

    /**
     * Erases the account's identity (TI-DELIVERY-002b decision 1): the
     * account row, and through its existing cascades every login identity,
     * session, waitlist entry and request budget; the sign-in codes sent to its
     * contact address; the free-text labels of its workspace's keys (the
     * technical rows stay for audit and the inventory); the workspace's
     * idempotency records and released EXACT reservations. Clears the
     * membership's workspace reference and marks the deletion ERASED. The spent
     * place, the participant marker and every cleanup charge remain.
     */
    fun eraseIdentity(
        accountId: HumanAccountId,
        workspaceId: WorkspaceId?,
        at: Instant,
    ): IdentityErasure

    /** The deletion record of [accountId], whatever its stage; null when the account was never deleted. */
    fun deletion(accountId: HumanAccountId): AccountDeletionRecord?

    /** Starts the record at DRAINING if the account has none. Idempotent. */
    fun openDeletion(
        accountId: HumanAccountId,
        at: Instant,
    )

    /** Moves the record FORWARD to [stage] (never back, never out of ERASED); stamps `erased_at` for ERASED. */
    fun advanceDeletion(
        accountId: HumanAccountId,
        stage: AccountDeletionStage,
        at: Instant,
    )

    /** Every unfinished deletion, oldest request first. */
    fun pendingDeletions(): List<AccountDeletionRecord>

    /** DELETION_PENDING accounts with no record (restored or hand-edited data): the timer adopts them. */
    fun unrecordedDeletions(): List<HumanAccountId>

    /** Restore reconciliation: permanently revokes every still-usable managed key of EVERY workspace. */
    fun revokeEveryManagedKey(at: Instant): Int
}
