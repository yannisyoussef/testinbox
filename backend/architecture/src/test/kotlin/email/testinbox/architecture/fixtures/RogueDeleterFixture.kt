package email.testinbox.architecture.fixtures

import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.StorageInspection

/**
 * NEVER production code. A deleter outside the four known ADR-035 paths: it
 * would free payload accounting with no deletion-debt row behind it.
 */
class RogueDeleterFixture(
    private val blobs: BlobStore,
    private val inspection: StorageInspection,
) {
    fun purge(key: String) {
        blobs.deletePrefix(key)
        inspection.deleteObject(key)
    }
}
