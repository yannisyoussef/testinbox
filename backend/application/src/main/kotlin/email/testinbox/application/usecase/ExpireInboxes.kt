package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.TestInboxConfig
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ExactAddressReservations
import email.testinbox.application.port.InboxMetrics
import email.testinbox.application.port.InboxRepository
import email.testinbox.application.port.InboxTeardown
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.RetentionPacing
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
    /**
     * TI-STORAGE-006E PR D (filesystem-containment contract §5.4): under `ALL`,
     * teardown runs in message batches, each only while the pacing allows it.
     * Null keeps today's whole-inbox teardown.
     */
    private val paced: PacedTeardown? = null,
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
        if (paced != null) {
            val (tornDown, held) = pacedTeardown(paced)
            deleted = tornDown
            deferred = held
        } else {
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
        }
        metrics.retentionBacklog(
            paced?.teardown?.oldestTeardownWaitingSince()?.let {
                maxOf(
                    0L,
                    java.time.Duration
                        .between(it, now)
                        .seconds,
                )
            } ?: 0L,
        )

        // Counts the EXPIRED transition, not the hard delete: expiry is the
        // lifecycle event (ADR-009), and the hard delete that follows is the
        // sweep reclaiming storage for something already expired.
        metrics.inboxExpired(expired)
        if (expiring + expired + deleted + deferred > 0) {
            log.info("inbox_sweep expiring={} expired={} hardDeleted={} deferred={}", expiring, expired, deleted, deferred)
        }
        return SweepReport(expiring, expired, deleted)
    }

    /**
     * Contract §5.4: inboxes taken fairly across workspaces; each torn down in
     * batches of exact message ids — each batch's per-message prefixes deleted
     * and PROVEN, then exactly its rows — and the next batch only while the
     * pacing allows it. The inbox row (and its prefix residue) goes once no
     * message is left. Returns (inboxes deleted, inboxes deferred).
     */
    private fun pacedTeardown(paced: PacedTeardown): Pair<Int, Int> {
        var deleted = 0
        var deferred = 0
        var batches = 0
        val fair =
            inboxes
                .findHardDeletable(config.sweepBatchSize)
                .groupBy { it.workspaceId }
                .values
                .map { it.iterator() }
                .let { iterators -> generateSequence { iterators.filter { it.hasNext() }.map { it.next() }.ifEmpty { null } }.flatten() }
        for (inbox in fair) {
            try {
                while (true) {
                    if (batches >= config.sweepBatchSize) return deleted to deferred // a sweep's work stays bounded
                    if (!paced.pacing.mayTearDown(paced.teardown.teardownWaitingSince(inbox.id))) {
                        // Not this inbox now, but the next may be past T_max: keep going.
                        log.info("inbox_teardown_paced D_est is at D_budget; this inbox resumes at a later sweep")
                        break
                    }
                    batches++
                    val ids = paced.teardown.messageIdsOf(inbox.id, paced.batch)
                    if (ids.isEmpty()) {
                        // No prefix delete here: whatever is left under the prefix has no row
                        // (a late or held object), so its deletion is row-free and belongs to
                        // the orphan sweep, which charges it by rule (P) (contract §2.1).
                        inboxes.hardDelete(inbox.id)
                        deleted++
                        break
                    }
                    ids.forEach { blobs.deletePrefix(ObjectKeys.messagePrefix(inbox.workspaceId, inbox.id, it)) }
                    paced.teardown.deleteMessages(inbox.id, ids)
                }
            } catch (e: RuntimeException) {
                deferred++
                log.warn(
                    "inbox_hard_delete_deferred a batch could not be fully deleted; its rows kept, retried next sweep: {}",
                    e.toString(),
                )
            }
        }
        return deleted to deferred
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExpireInboxes::class.java)
    }
}

/** Paced retention's collaborators (contract §5.4): the policy, the batch port, the batch size. */
class PacedTeardown(
    val pacing: RetentionPacing,
    val teardown: InboxTeardown,
    val batch: Int = 200,
) {
    init {
        require(batch > 0) { "a teardown batch has at least one message" }
    }
}
