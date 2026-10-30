package email.testinbox.domain.identity

import java.time.Duration
import java.time.Instant

/** The operator's account-state commands (ADR-036 §5; ACCOUNT-LIFECYCLE.md). There is no public request form. */
enum class AccountLifecycleAction {
    SUSPEND,
    RESUME,
    DELETE,
}

/**
 * The account-state rules, independent of storage and transport.
 *
 * - Suspension and deletion-pending revoke access permanently. Resuming
 *   changes the state only; it never revives a revoked session or key, so a
 *   resumed participant signs in again and issues new keys.
 * - Deletion-pending is terminal for this release: it cannot be suspended or
 *   resumed. Neither state releases the spent participant place or its share.
 * - Repeating a command is idempotent ([Decision.Unchanged]), so an operator
 *   can re-run it to re-check draining without changing anything.
 */
object AccountLifecycle {
    sealed interface Decision {
        val to: HumanAccountState

        data class Change(
            override val to: HumanAccountState,
        ) : Decision

        data class Unchanged(
            override val to: HumanAccountState,
        ) : Decision

        data class Refused(
            val from: HumanAccountState,
            val reason: String,
        ) : Decision {
            override val to: HumanAccountState get() = from
        }
    }

    fun decide(
        from: HumanAccountState,
        action: AccountLifecycleAction,
    ): Decision =
        when (action) {
            AccountLifecycleAction.SUSPEND -> {
                when (from) {
                    HumanAccountState.ACTIVE -> Decision.Change(HumanAccountState.SUSPENDED)
                    HumanAccountState.SUSPENDED -> Decision.Unchanged(from)
                    HumanAccountState.DELETION_PENDING -> Decision.Refused(from, "a deletion-pending account cannot be suspended")
                }
            }

            AccountLifecycleAction.RESUME -> {
                when (from) {
                    HumanAccountState.SUSPENDED -> Decision.Change(HumanAccountState.ACTIVE)
                    HumanAccountState.ACTIVE -> Decision.Unchanged(from)
                    HumanAccountState.DELETION_PENDING -> Decision.Refused(from, "a deletion-pending account cannot be resumed")
                }
            }

            AccountLifecycleAction.DELETE -> {
                when (from) {
                    HumanAccountState.ACTIVE, HumanAccountState.SUSPENDED -> Decision.Change(HumanAccountState.DELETION_PENDING)
                    HumanAccountState.DELETION_PENDING -> Decision.Unchanged(from)
                }
            }
        }

    /** Every non-active state revokes browser sessions and managed keys, and refuses new ingestion. */
    fun revokesAccess(state: HumanAccountState): Boolean = state != HumanAccountState.ACTIVE
}

/**
 * The observable stages of an account deletion (TI-DELIVERY-002b owner
 * decision 1). The workflow only moves forward; every stage is re-entrant, so
 * a run interrupted between two stages resumes where it stopped.
 */
enum class AccountDeletionStage {
    /** Access is revoked; reservations made before the fence may still be in flight. Nothing is deleted yet. */
    DRAINING,

    /**
     * Every inbox is DELETED. Waiting for the retention sweep to remove the
     * inbox rows (objects are proven deleted before rows) and for every EXACT
     * address cooldown of the workspace to elapse.
     */
    CONTENT_DELETING,

    /** Content is gone and the contact and login identity has been erased. Terminal. */
    ERASED,
}

/** What the content gate reads, under the lifecycle locks, before identity may be erased. */
data class AccountContentRemaining(
    val reservationsHeld: Long,
    val inboxRows: Long,
    /** EXACT reservations still ACTIVE, or in COOLDOWN with `available_at` in the future. */
    val exactCooldowns: Long,
) {
    val none: Boolean get() = reservationsHeld == 0L && inboxRows == 0L && exactCooldowns == 0L

    val stage: AccountDeletionStage
        get() =
            when {
                reservationsHeld > 0 -> AccountDeletionStage.DRAINING
                none -> AccountDeletionStage.ERASED
                else -> AccountDeletionStage.CONTENT_DELETING
            }

    companion object {
        val NONE = AccountContentRemaining(0, 0, 0)
    }
}

/**
 * When an unfinished deletion is overdue. The threshold is the longest the
 * existing machinery may legitimately take — the EXACT cooldown the deleted
 * addresses re-enter, plus the paced-retention `T_max` within which the sweep
 * tears every inbox down whatever its pacing — plus slack for draining and the
 * operator timer's own period (POLICY-002b.md).
 */
class AccountDeletionPolicy(
    val overdueAfter: Duration,
) {
    init {
        require(!overdueAfter.isNegative && !overdueAfter.isZero) { "the overdue threshold must be positive" }
    }

    fun overdue(
        stage: AccountDeletionStage,
        requestedAt: Instant,
        now: Instant,
    ): Boolean = stage != AccountDeletionStage.ERASED && Duration.between(requestedAt, now) > overdueAfter

    companion object {
        val SLACK: Duration = Duration.ofHours(24)

        fun derived(
            exactCooldown: Duration,
            retentionMaxDelay: Duration,
        ): AccountDeletionPolicy = AccountDeletionPolicy(exactCooldown.plus(retentionMaxDelay).plus(SLACK))
    }
}
