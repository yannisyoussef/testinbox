package email.testinbox.storage

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.DeletedObject
import software.amazon.awssdk.services.s3.model.S3Error

/**
 * Filesystem-containment contract §2.2 (f) / §4.6 (TI-STORAGE-006E): a
 * `DeleteObjects` that answers 200 with per-key errors has NOT deleted the
 * prefix, and retention must not go on to delete the rows. The response
 * handling is a pure function, pinned here; the real MinIO cannot be made to
 * fail one key on demand.
 */
class PartialDeleteTest {
    @Test
    fun `a response with no errors passes`() {
        S3BlobStore.failOnPartialDelete(
            DeleteObjectsResponse.builder().deleted(DeletedObject.builder().key("ws/ib/m/raw.eml").build()).build(),
        )
        S3BlobStore.failOnPartialDelete(DeleteObjectsResponse.builder().build())
    }

    @Test
    fun `a response with any per-key error throws, naming the codes and never the keys`() {
        val response =
            DeleteObjectsResponse
                .builder()
                .deleted(DeletedObject.builder().key("ws/ib/m1/raw.eml").build())
                .errors(
                    S3Error
                        .builder()
                        .key("ws/ib/m2/raw.eml")
                        .code("InternalError")
                        .message("drive timeout")
                        .build(),
                    S3Error
                        .builder()
                        .key("ws/ib/m3/raw.eml")
                        .code("InternalError")
                        .build(),
                    S3Error.builder().key("ws/ib/m4/raw.eml").build(),
                ).build()

        val failure = assertThrows<PartialDeleteException> { S3BlobStore.failOnPartialDelete(response) }

        failure.message shouldContain "3 of the listed objects were not deleted"
        failure.message shouldContain "InternalError=2"
        failure.message shouldContain "unknown=1"
        failure.message?.contains("ws/ib") shouldBe false
    }
}
