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
    /** TI-STORAGE-006E: rule (P) before every deletion, and the database record of each pass. */
    private val containment: Containment = Containment.NONE,
) {
    /**
     * The sweep's containment hooks (TI-STORAGE-006E).
     * - [rowFree]: rule (P) before every deletion here, none of which has a row trigger.
     * - [runs]: the V10 database record of each full pass, which activation gate F reads.
     */
    class Containment(
        val rowFree: email.testinbox.application.storage.RowFreeDebt = email.testinbox.application.storage.RowFreeDebt.NONE,
        val runs: email.testinbox.application.port.SweepRuns = email.testinbox.application.port.SweepRuns.NONE,
    ) {
        companion object {
            val NONE = Containment()
        }
    }

    private val rowFree = containment.rowFree
    private val runs = containment.runs

    fun sweep(): Int =
        try {
            // Opened BEFORE the listing: the run's database-issued order is what proves
            // the pass began after the counts were trusted. A pass that throws stays open.
            // The record is evidence for activation gate F, never a precondition of
            // cleanup: if it cannot be written (a missing V10 grant), the sweep still
            // runs, and gate F simply finds no completed run.
            val run = runCatching { runs.begin() }.onFailure { recordFailed("begin", it) }.getOrNull()
            fullSweep()
                .also { (_, listed) ->
                    if (run != null) runCatching { runs.complete(run, listed) }.onFailure { recordFailed("complete", it) }
                }.first
                .also {
                    // The completion marker ADR-035 §14 (b) needs: a FULL pass over the
                    // bucket finished at this instant. A pass that threw never sets it.
                    metrics.orphanSweepCompleted(clock.instant())
                    metrics.orphanSweepFinished(ok = true)
                }
        } catch (e: RuntimeException) {
            metrics.orphanSweepFinished(ok = false)
            throw e
        }

    private fun fullSweep(): Pair<Int, Long> {
        val threshold = clock.instant().minus(minAge)
        var removed = 0
        for (key in blobs.listKeysOlderThan("", threshold)) {
            if (key.startsWith(email.testinbox.application.storage.ReleaseStaleReservations.PROBE_PREFIX)) {
                // A witness or breaker probe is deleted by the call that wrote it;
                // one this old outlived a failed listing. It has no row, no
                // reservation and no debt row, so nothing else would ever free it
                // (filesystem-containment contract §5.3, TI-STORAGE-006E).
                if (deleteRowFree(key, "probe-residue") == Deletion.DELETED) removed++
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
                when (deleteRowFree(key, "orphan-sweep")) {
                    Deletion.DELETED -> {
                        removed++
                    }

                    Deletion.REFUSED -> {
                        // A late object rule (P) refused: held with a row of its own, so it
                        // occupies a slot and the latch stays set until it is gone. A storage
                        // error is no refusal: that key is simply retried next pass.
                        if (ambiguity.wasAmbiguous(key, AMBIGUITY_RETENTION)) holdLateObject(key)
                    }

                    Deletion.FAILED -> {}
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
        val listed = inspection.listedPayloadBytes()
        metrics.physicalListedBytes(listed)
        return removed to listed
    }

    /**
     * Rule (P) first: a pending row of the object's size, committed before the
     * delete; refused, the object stays (and a late object keeps its slot).
     * Proven absent after the delete, the row is resolved.
     */
    private enum class Deletion { DELETED, REFUSED, FAILED }

    private fun deleteRowFree(
        key: String,
        source: String,
    ): Deletion =
        try {
            if (!rowFree.beforeDelete(key, if (rowFree.charges) inspection.objectSize(key) else null, source)) {
                Deletion.REFUSED
            } else {
                blobs.delete(key)
                if (!inspection.objectExists(key)) rowFree.afterProvenAbsent(key)
                Deletion.DELETED
            }
        } catch (e: RuntimeException) {
            // One key's storage or lock error never stops the pass: the rest of the
            // bucket, the resolver and the multipart pass still run; this key is retried.
            log.warn("orphan_blob_sweep_key_failed the key is kept and retried next pass: {}", e.toString())
            Deletion.FAILED
        }

    private fun holdLateObject(key: String) {
        try {
            ambiguity.holdLateObject(key, inspection.objectSize(key) ?: 0)
        } catch (e: RuntimeException) {
            // Unheld, the late object's slot is not counted until the next pass holds it;
            // the latch set above stays, so admission is closed meanwhile.
            log.error("orphan_blob_sweep_hold_failed a refused late object is not yet held; retried next pass: {}", e.toString())
        }
    }

    private fun recordFailed(
        step: String,
        e: Throwable,
    ) = log.warn(
        "orphan_blob_sweep_run_record_failed step={} the sweep continues; gate F will find no completed run: {}",
        step,
        e.toString(),
    )

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        /** ADR-035 §9 point 2: deleted orphans are matched against ambiguity rows of the last 24 h. */
        val AMBIGUITY_RETENTION: Duration = Duration.ofHours(24)
        val log = LoggerFactory.getLogger(OrphanBlobSweep::class.java)
    }
}
