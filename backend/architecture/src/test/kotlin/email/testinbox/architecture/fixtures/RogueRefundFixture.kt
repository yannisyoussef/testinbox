package email.testinbox.architecture.fixtures

import email.testinbox.application.port.RateCharge
import email.testinbox.application.port.RateLimiter
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory

/**
 * NEVER production code. A caller outside the inbound use case giving a
 * charge back, for example after a failure seen from T1 on — the shape the
 * refund rule exists to catch.
 */
class RogueRefundFixture(
    private val limiter: RateLimiter,
) {
    fun afterAnyFailure(workspaceId: WorkspaceId) {
        limiter.refund(listOf(RateCharge(workspaceId, RateCategory.INGEST)))
    }
}
