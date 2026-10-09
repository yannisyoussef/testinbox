package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageAmbiguity
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.StorageLatch
import email.testinbox.application.port.StorageProtocolMetrics
import email.testinbox.application.port.StorageReservations
import email.testinbox.domain.MessageId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Deterministic orphan cleanup (ADR-005, data-ownership.md), under ADR-035 §7
 * semantics.
 *
 * - **Old enough to judge.** Objects younger than [minAge] are skipped.
 * - **One statement decides.** A key is deleted only when ONE database
 *   statement confirms that its message id has no message row, no reservation,
 *   and no unresolved ambiguity for the key. Three separate checks would let a
 *   T2 commit between them, and the sweep would delete committed content.
 * - **Late objects latch.** An orphan whose key was ambiguous in the last
 *   24 h (resolved or not) is an ambiguous upload that landed after its
 *   reservation and its verification: the admission latch is set, and
 *   committed, before it is deleted (§9 point 2).
 * - **Multipart residue.** TestInbox never initiates a multipart upload, so
 *   any incomplete upload in the bucket (listed bucket-wide, probe M1) is a
 *   defect. Past [minAge] it is aborted and alarmed.
 *
 * It also publishes the physical side of reconciliation: the payload bytes
 * actually listed in the bucket (§10).
 */
class OrphanBlobSweep(
    private val blobs: BlobStore,
    private val reservations: StorageReservations,
    private val ambiguity: StorageAmbiguity,
    private val latch: StorageLatch,
    private val inspection: StorageInspection,
    private val clock: Clock,
    private val minAge: Duration = Duration.ofHours(1),
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
    /** TI-STORAGE-006E PR D: rule (P) before every deletion here; none of them has a row trigger. */
    private val rowFree: email.testinbox.application.storage.RowFreeDebt =
        email.testinbox.application.storage.RowFreeDebt.NONE,
) {
    fun sweep(): Int =
        try {
            fullSweep().also {
                // The completion marker ADR-035 §14 (b) needs: a FULL pass over the
                // bucket finished at this instant. A pass that threw never sets it.
                metrics.orphanSweepCompleted(clock.instant())
                metrics.orphanSweepFinished(ok = true)
            }
        } catch (e: RuntimeException) {
            metrics.orphanSweepFinished(ok = false)
            throw e
        }

    private fun fullSweep(): Int {
        val threshold = clock.instant().minus(minAge)
        var removed = 0
        for (key in blobs.listKeysOlderThan("", threshold)) {
            if (key.startsWith(email.testinbox.application.storage.ReleaseStaleReservations.PROBE_PREFIX)) {
                // A witness or breaker probe is deleted by the call that wrote it;
                // one this old outlived a failed listing. It has no row, no
                // reservation and no debt row, so nothing else would ever free it
                // (filesystem-containment contract §5.3, TI-STORAGE-006E).
                if (deleteRowFree(key, "probe-residue")) removed++
                continue
            }
            val messageId = ObjectKeys.messageIdOf(key)?.let(::parseUuid) ?: continue
            if (reservations.isOrphan(MessageId(messageId), key)) {
                if (ambiguity.wasAmbiguous(key, AMBIGUITY_RETENTION)) {
                    latch.latch("late object found by the orphan sweep")
                    metrics.latched(true)
                    metrics.lateObject()
                    log.error("storage_late_object an ambiguous upload surfaced as an orphan; admission LATCHED")
                }
                if (deleteRowFree(key, "orphan-sweep")) {
                    removed++
                } else if (ambiguity.wasAmbiguous(key, AMBIGUITY_RETENTION)) {
                    // A late object rule (P) refused: held with a row of its own, so it
                    // occupies a slot and the latch stays set until it is gone.
                    runCatching { ambiguity.holdLateObject(key, inspection.objectSize(key) ?: 0) }
                }
            }
        }
        if (removed > 0) log.info("orphan_blob_sweep removed={}", removed)
        // Pending rows whose writer crashed, or whose probe PUT was ambiguous (§5.2).
        rowFree.resolveStale(inspection).let { if (it > 0) log.info("orphan_blob_sweep resolved_pending={}", it) }

        val incomplete = inspection.incompleteUploads()
        metrics.incompleteUploads(incomplete.size)
        incomplete.filter { it.initiated.isBefore(threshold) }.forEach { upload ->
            log.error("storage_incomplete_upload found in the bucket; TestInbox never starts one. Aborting it.")
            inspection.abortIncompleteUpload(upload)
        }
        metrics.physicalListedBytes(inspection.listedPayloadBytes())
        return removed
    }

    /**
     * Rule (P) first: a pending row of the object's size, committed before the
     * delete; refused, the object stays (and a late object keeps its slot).
     * Proven absent after the delete, the row is resolved.
     */
    private fun deleteRowFree(
        key: String,
        source: String,
    ): Boolean =
        try {
            if (!rowFree.beforeDelete(key, if (rowFree.charges) inspection.objectSize(key) else null, source)) {
                false
            } else {
                blobs.delete(key)
                if (!inspection.objectExists(key)) rowFree.afterProvenAbsent(key)
                true
            }
        } catch (e: RuntimeException) {
            // One key's storage or lock error never stops the pass: the rest of the
            // bucket, the resolver and the multipart pass still run; this key is retried.
            log.warn("orphan_blob_sweep_key_failed the key is kept and retried next pass: {}", e.toString())
            false
        }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        /** ADR-035 §9 point 2: deleted orphans are matched against ambiguity rows of the last 24 h. */
        val AMBIGUITY_RETENTION: Duration = Duration.ofHours(24)
        val log = LoggerFactory.getLogger(OrphanBlobSweep::class.java)
    }
}
