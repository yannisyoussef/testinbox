package email.testinbox.architecture.fixtures

import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.ReservedUpload
import email.testinbox.storage.fenced.FencedUploader

/**
 * NEVER production code. A fenced write outside the guarded protocol (no
 * reservation behind it), and an uploader driven outside the blob store.
 */
class RogueReservedPutFixture(
    private val blobs: BlobStore,
    private val uploader: FencedUploader,
) {
    fun write(upload: ReservedUpload) {
        blobs.putReserved(upload)
        uploader.put(upload)
    }
}
