package email.testinbox.domain.identity

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
