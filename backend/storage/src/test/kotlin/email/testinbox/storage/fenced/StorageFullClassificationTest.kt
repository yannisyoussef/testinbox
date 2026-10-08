package email.testinbox.storage.fenced

import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.UploadOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Filesystem-containment contract §8 (TI-STORAGE-006E): the pinned MinIO's two
 * full-filesystem answers are `STORAGE_FULL`, and NOTHING else is: a `500` is
 * storage-full only when its message names ENOSPC, never by status alone. The
 * bodies are the ones PR #81's lab B recorded.
 */
class StorageFullClassificationTest {
    private fun outcome(
        status: Int,
        code: String?,
        message: String?,
    ) = FencedUploader.classify(FencedUploader.Response(status, code, message))

    @Test
    fun `507 XMinioStorageFull is storage-full`() {
        outcome(507, "XMinioStorageFull", "Storage backend has reached its minimum free drive threshold.") shouldBe
            UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL)
    }

    @Test
    fun `500 InternalError naming ENOSPC is storage-full, in any case`() {
        val enospc =
            "We encountered an internal error, please try again.: cause(write /data/.minio.sys/tmp/x/part.1: no space left on device)"
        outcome(500, "InternalError", enospc) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL)
        outcome(500, "InternalError", enospc.uppercase()) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL)
    }

    @Test
    fun `a plain 500, or a 500 with any other message, is a server error - never storage-full by status alone`() {
        outcome(500, "InternalError", null) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
        outcome(500, "InternalError", "We encountered an internal error, please try again.") shouldBe
            UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
        outcome(503, "SlowDown", "no space left on device") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
    }

    @Test
    fun `507 without MinIO's code is not assumed to be storage-full`() {
        outcome(507, null, null) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
        outcome(507, "InsufficientStorage", null) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
    }

    @Test
    fun `storage-full is ambiguous - the reservation stays charged and the slot held`() {
        UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL).definitive shouldBe false
    }
}
