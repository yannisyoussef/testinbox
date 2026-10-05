package email.testinbox.architecture.fixtures

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/**
 * NEVER production code. The shape of the unfenced write ADR-035 removed, kept
 * only so the architecture test can prove its rule detects it.
 */
class UnfencedPutFixture(
    private val s3: S3Client,
) {
    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket("b")
                .key(key)
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }
}
