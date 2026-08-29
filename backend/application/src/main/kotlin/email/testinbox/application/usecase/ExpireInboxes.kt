package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.inbox.AddressMode
import java.time.Clock
import org.slf4j.LoggerFactory

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
) {
    data class SweepReport(val markedExpiring: Int, val markedExpired: Int, val hardDeleted: Int)

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

        for (inbox in inboxes.findHardDeletable(config.sweepBatchSize)) {
            // Blob prefix delete first: idempotent, retried on the next sweep if the row delete fails.
            blobs.deletePrefix(ObjectKeys.inboxPrefix(inbox.workspaceId, inbox.id))
            inboxes.hardDelete(inbox.id)
            deleted++
        }

        if (expiring + expired + deleted > 0) {
            log.info("inbox_sweep expiring={} expired={} hardDeleted={}", expiring, expired, deleted)
        }
        return SweepReport(expiring, expired, deleted)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExpireInboxes::class.java)
    }
}
