package email.testinbox.application.query

import email.testinbox.application.LimitsConfig
import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TenantReader
import email.testinbox.application.port.WaitSlots
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace

/** The authenticated key's own workspace and project, read by their ids. */
data class WorkspaceView(
    val workspace: Workspace,
    val project: Project?,
    /** Mail domain routable to this deployment (`testinbox.mail-domain`). */
    val mailDomain: String,
)

/** One quota dimension (ADR-027 §4): the allowance and the consumption derived from rows. */
data class QuotaView(
    val dimension: QuotaDimension,
    val limit: Long,
    val current: Long,
)

/** One rate category (ADR-027 §2): the policy, and the tokens left right now when known. */
data class RateView(
    val category: RateCategory,
    val limit: Long,
    val refillPerSecond: Double,
    /** Null when the limiter cannot say without spending (a fake); never a guess. */
    val remaining: Long?,
)

data class WorkspaceUsageView(
    /** `testinbox.limits.enabled`: with it off, every figure is reported and nothing is refused. */
    val enforced: Boolean,
    val quotas: List<QuotaView>,
    val rates: List<RateView>,
)

/**
 * Thin tenancy reads (ADR-024 allows simple reads to bypass use-case
 * ceremony). The workspace is always the authenticated key's own; no id is
 * ever taken from a request. The usage read reports the same policy the use
 * cases enforce — one `LimitsConfig` — and consumption **derived from rows**
 * (ADR-027 §4), so what it says can never drift from what a create would be
 * refused on.
 */
class WorkspaceQueries(
    private val tenants: TenantReader,
    private val mailDomain: String,
    private val limits: LimitsConfig,
    private val quotas: WorkspaceQuotaState,
    private val rateLimiter: RateLimiter,
    private val waitSlots: WaitSlots,
) {
    fun workspace(
        workspaceId: WorkspaceId,
        projectId: ProjectId,
    ): WorkspaceView? {
        val workspace = tenants.findWorkspace(workspaceId) ?: return null
        return WorkspaceView(workspace, tenants.findProject(workspaceId, projectId), mailDomain)
    }

    fun usage(workspaceId: WorkspaceId): WorkspaceUsageView {
        val policy = limits.quotas
        val quotaViews =
            listOf(
                QuotaView(QuotaDimension.ACTIVE_INBOXES, policy.maxActiveInboxes, quotas.activeInboxCount(workspaceId)),
                QuotaView(QuotaDimension.STORED_BYTES, policy.maxStoredBytes, quotas.storedBytes(workspaceId)),
                QuotaView(QuotaDimension.CONCURRENT_WAITS, policy.maxConcurrentWaits, waitSlots.held(workspaceId)),
            )
        val rateViews =
            RateCategory.entries.map { category ->
                val rate = limits.rateFor(category)
                RateView(category, rate.capacity, rate.refillPerSecond, rateLimiter.peek(workspaceId, category))
            }
        return WorkspaceUsageView(enforced = limits.enabled, quotas = quotaViews, rates = rateViews)
    }
}
