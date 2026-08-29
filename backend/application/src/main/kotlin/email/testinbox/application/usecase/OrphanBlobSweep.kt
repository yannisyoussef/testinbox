package email.testinbox.application.usecase

import email.testinbox.application.ObjectKeys
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.MessageRepository
import email.testinbox.domain.MessageId
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Deterministic orphan cleanup (ADR-005 / data-ownership.md): the write
 * order is storage-first, so a crash between blob write and DB commit can
 * only leave a blob with no referencing Message row. Objects older than
 * [minAge] whose message id has no row are deleted.
 */
class OrphanBlobSweep(
    private val blobs: BlobStore,
    private val messages: MessageRepository,
    private val clock: Clock,
    private val minAge: Duration = Duration.ofHours(1),
) {
    fun sweep(): Int {
        val threshold = clock.instant().minus(minAge)
        var removed = 0
        for (key in blobs.listKeysOlderThan("", threshold)) {
            val messageId = ObjectKeys.messageIdOf(key)?.let(::parseUuid) ?: continue
            if (!messages.exists(MessageId(messageId))) {
                blobs.delete(key)
                removed++
            }
        }
        if (removed > 0) log.info("orphan_blob_sweep removed={}", removed)
        return removed
    }

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()

    private companion object {
        val log = LoggerFactory.getLogger(OrphanBlobSweep::class.java)
    }
}
