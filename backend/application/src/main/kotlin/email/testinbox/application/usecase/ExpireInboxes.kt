package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.inbox.AddressMode
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * TTL lifecycle sweep (ADR-009): ACTIVE → EXPIRING (grace window honoring
 * in-flight deliveries) → EXPIRED → hard delete of rows and object-storage
 * prefix. Idempotent and safe to run concurrently with inbound delivery —
 * transitions are guarded state updates, and blob deletion precedes row
 * deletion so a crash leaves a retryable inbox, never orphaned rows.
 */
class ExpireInboxes(
    private val inboxes: InboxRepository,
    private val reservations: ExactAddressReservations,
    private val blobs: BlobStore,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val config: TestInboxConfig,
    private val metrics: InboxMetrics = InboxMetrics.NOOP,
) {
    data class SweepReport(
        val markedExpiring: Int,
        val markedExpired: Int,
        val hardDeleted: Int,
    )

    fun sweep(): SweepReport {
        val now = clock.instant()
        var expiring = 0
        var expired = 0
        var deleted = 0

        for (inbox in inboxes.findExpiredActive(now, config.sweepBatchSize)) {
            if (inboxes.transitionToExpiring(inbox.id, now.plus(config.expiryGrace))) expiring++
        }

        for (inbox in inboxes.findExpiringPastGrace(now, config.sweepBatchSize)) {
            val transitioned =
                tx.required {
                    val moved = inboxes.transitionToExpired(inbox.id)
                    if (moved && inbox.addressMode == AddressMode.EXACT) {
                        reservations.startCooldown(inbox.id, now.plus(config.exactCooldown))
                    }
                    moved
                }
            if (transitioned) expired++
        }

        var deferred = 0
        for (inbox in inboxes.findHardDeletable(config.sweepBatchSize)) {
            // Blob prefix delete first, and PROVEN (a per-key error throws), then the
            // rows: an object left behind with its rows gone would be allocated bytes
            // no ledger figure describes (filesystem-containment contract §4.6). A
            // prefix that cannot be fully deleted keeps its rows and is retried next
            // sweep — on its own, so one stuck inbox never blocks the batch behind it.
            try {
                blobs.deletePrefix(ObjectKeys.inboxPrefix(inbox.workspaceId, inbox.id))
            } catch (e: RuntimeException) {
                deferred++
                log.warn(
                    "inbox_hard_delete_deferred the blob prefix could not be fully deleted; rows kept, retried next sweep: {}",
                    e.toString(),
                )
                continue
            }
            inboxes.hardDelete(inbox.id)
            deleted++
        }

        // Counts the EXPIRED transition, not the hard delete: expiry is the
        // lifecycle event (ADR-009), and the hard delete that follows is the
        // sweep reclaiming storage for something already expired.
        metrics.inboxExpired(expired)
        if (expiring + expired + deleted + deferred > 0) {
            log.info("inbox_sweep expiring={} expired={} hardDeleted={} deferred={}", expiring, expired, deleted, deferred)
        }
        return SweepReport(expiring, expired, deleted)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExpireInboxes::class.java)
    }
}
