package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageInspection
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
    private val inspection: StorageInspection,
    private val clock: Clock,
    private val minAge: Duration = Duration.ofHours(1),
    private val metrics: StorageProtocolMetrics = StorageProtocolMetrics.NOOP,
) {
    fun sweep(): Int {
        val threshold = clock.instant().minus(minAge)
        var removed = 0
        for (key in blobs.listKeysOlderThan("", threshold)) {
            val messageId = ObjectKeys.messageIdOf(key)?.let(::parseUuid) ?: continue
            if (reservations.isOrphan(MessageId(messageId), key)) {
                blobs.delete(key)
                removed++
            }
        }
        if (removed > 0) log.info("orphan_blob_sweep removed={}", removed)

        val incomplete = inspection.incompleteUploads()
        metrics.incompleteUploads(incomplete.size)
        incomplete.filter { it.initiated.isBefore(threshold) }.forEach { upload ->
            log.error("storage_incomplete_upload found in the bucket; TestInbox never starts one. Aborting it.")
            inspection.abortIncompleteUpload(upload)
        }
        metrics.physicalListedBytes(inspection.listedPayloadBytes())
        return removed
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        val log = LoggerFactory.getLogger(OrphanBlobSweep::class.java)
    }
}
