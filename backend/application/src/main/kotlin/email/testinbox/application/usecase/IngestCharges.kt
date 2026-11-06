package email.testinbox.application.usecase

import email.testinbox.application.port.RateCharge
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `INGEST` tokens ONE attempt actually took, per recipient copy, and that
 * attempt's single settlement (owner decision 2026-11-06, ADR-027 §4 as
 * amended). One instance per [ReceiveInboundDelivery.execute] call, never
 * shared: it is the refund token bound to the attempt.
 *
 * A copy is recorded only once its inbox charge returned allowed, so a charge
 * whose own outcome is unknown (the limiter threw) is never recorded and never
 * given back. The workspace charge is recorded only when it, too, returned
 * allowed. A copy the inbox budget refused took nothing and is not recorded.
 *
 * Settlement happens exactly once. [claimRefund] wins it at most once and
 * hands back the charges; [keep] wins it for an attempt whose charges stand
 * (T1 was invoked, or the event completed). Whichever comes first, every later
 * claim returns null, from any thread.
 */
internal class IngestCharges {
    /** What one resolved recipient copy was charged: its inbox token, and its workspace token when that was taken too. */
    data class CopyCharge(
        val workspaceId: WorkspaceId,
        val inboxId: InboxId,
        val workspaceTaken: Boolean,
    )

    private val copies = mutableListOf<CopyCharge>()
    private val settled = AtomicBoolean(false)

    /** Records a copy whose inbox token was taken (and, when [workspaceTaken], its workspace token). */
    fun took(copy: CopyCharge) {
        check(!settled.get()) { "an attempt's charges are recorded before it settles" }
        copies += copy
    }

    /** The copies charged so far, in charge order. */
    fun copies(): List<CopyCharge> = copies.toList()

    /** One [RateCharge] per token taken: the inbox bucket of every copy, the workspace bucket of those charged there too. */
    fun taken(): List<RateCharge> =
        copies.flatMap { copy ->
            listOfNotNull(
                RateCharge(copy.workspaceId, RateCategory.INGEST, copy.inboxId),
                RateCharge(copy.workspaceId, RateCategory.INGEST).takeIf { copy.workspaceTaken },
            )
        }

    /**
     * Wins this attempt's settlement as a refund: the charges to give back, or
     * null when the attempt already settled (refunded or kept) or took nothing.
     */
    fun claimRefund(): List<RateCharge>? {
        if (!settled.compareAndSet(false, true)) return null
        return taken().takeIf { it.isNotEmpty() }
    }

    /** Settles this attempt with its charges standing: no refund can follow. */
    fun keep() {
        settled.set(true)
    }
}
