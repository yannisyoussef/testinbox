package email.testinbox.architecture.fixtures

import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** NEVER production code. An unfenced write through the async client, for the rule to catch. */
class UnfencedAsyncPutFixture(
    private val s3: S3AsyncClient,
) {
    fun put(
        key: String,
        bytes: ByteArray,
    ) {
        s3
            .putObject(
                PutObjectRequest
                    .builder()
                    .bucket("b")
                    .key(key)
                    .build(),
                AsyncRequestBody.fromBytes(bytes),
            ).join()
    }
}
