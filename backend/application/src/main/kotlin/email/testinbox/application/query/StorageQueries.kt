package email.testinbox.application.query

import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.StorageVisibility
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageUsage

/**
 * ADR-035 §13's `StorageUsage` wire model for one scope, computed from the
 * accounting figures and the effective policy. The wire names are the ADR's:
 * `storedBytes`, never the internal `committedBytes`.
 *
 * - `overLimit` is `stored + reserved > limit`: equality is not over, and at
 *   equality `availableBytes` is 0.
 * - Arithmetic is checked. A sum that does not fit a signed 64-bit figure is
 *   corrupt accounting, reported as an internal failure, never clamped into
 *   a plausible-looking quota.
 * - Negative internal figures (accounting drift, which reconciliation repairs)
 *   pass through to `storedBytes`/`reservedBytes` unclamped; only
 *   `availableBytes` has a floor of zero, by the ADR's formula.
 */
data class StorageUsageView(
    val limitBytes: Long,
    val storedBytes: Long,
    val reservedBytes: Long,
    val availableBytes: Long,
    val overLimit: Boolean,
) {
    companion object {
        /** §13a: `availableBytes = max(0, limit − stored − reserved)`. */
        fun workspace(
            policy: StorageCapacityPolicy,
            usage: StorageUsage,
        ): StorageUsageView = checked { of(policy.workspaceLimitBytes, usage, headroom(policy.workspaceLimitBytes, usage)) }

        /**
         * §13b: the inbox's own limit state, but `availableBytes` is the
         * minimum of the inbox's and its workspace's headroom, since both
         * ceilings must admit a copy. Global headroom never enters it (§13d).
         */
        fun inbox(
            policy: StorageCapacityPolicy,
            inbox: StorageUsage,
            workspace: StorageUsage,
        ): StorageUsageView =
            checked {
                of(
                    policy.inboxLimitBytes,
                    inbox,
                    minOf(headroom(policy.inboxLimitBytes, inbox), headroom(policy.workspaceLimitBytes, workspace)),
                )
            }

        private fun of(
            limit: Long,
            usage: StorageUsage,
            headroom: Long,
        ): StorageUsageView =
            StorageUsageView(
                limitBytes = limit,
                storedBytes = usage.committedBytes,
                reservedBytes = usage.reservedBytes,
                availableBytes = maxOf(0L, headroom),
                overLimit = usage.usedBytes > limit,
            )

        /** `limit − stored − reserved`, possibly negative. */
        private fun headroom(
            limit: Long,
            usage: StorageUsage,
        ): Long = Math.subtractExact(limit, usage.usedBytes)

        private fun <T> checked(block: () -> T): T =
            try {
                block()
            } catch (overflow: ArithmeticException) {
                throw StorageAccountingOverflowException("storage usage overflowed a signed 64-bit figure", overflow)
            }
    }
}

/** What every inbox representation carries beside the aggregate (ADR-035 §13b). */
data class InboxStorageView(
    val storage: StorageUsageView,
    val refusals: StorageRefusalSnapshot,
)

/**
 * Thin read-side service for ADR-035 storage visibility (ADR-024 allows
 * simple reads to bypass use-case ceremony). Tenant-scoped by construction:
 * the workspace is always the caller's, and the figures come from the same
 * effective [policy] T1 admits against, so what a tenant sees as a limit is
 * what admission applies.
 */
class StorageQueries(
    private val visibility: StorageVisibility,
    private val policy: StorageCapacityPolicy,
) {
    /** `GET /v1/workspace/storage`: the authenticated key's own workspace, nothing else (§13a). */
    fun workspace(workspaceId: WorkspaceId): StorageUsageView =
        StorageUsageView.workspace(policy, visibility.read(workspaceId, emptySet()).workspace)

    /** The live storage members of one inbox representation (§13b). */
    fun inbox(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
    ): InboxStorageView = inboxes(workspaceId, setOf(inboxId)).getValue(inboxId)

    /** Set-based, so a future inbox list computes a page in one read (§13b). */
    fun inboxes(
        workspaceId: WorkspaceId,
        inboxIds: Set<InboxId>,
    ): Map<InboxId, InboxStorageView> {
        if (inboxIds.isEmpty()) return emptyMap()
        val figures = visibility.read(workspaceId, inboxIds)
        return inboxIds.associateWith { id ->
            val inbox = figures.inbox(id)
            InboxStorageView(StorageUsageView.inbox(policy, inbox.usage, figures.workspace), inbox.refusals)
        }
    }
}
