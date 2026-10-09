package email.testinbox.storage

import email.testinbox.application.port.BlobStoreMetrics
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Error
import software.amazon.awssdk.services.s3.model.S3Object
import java.lang.reflect.Proxy

/**
 * The deletion paths of the filesystem-containment contract §4.6, wired
 * through the real adapters with a stubbed S3 client (TI-STORAGE-006E):
 * - `S3BlobStore.deletePrefix` really applies the partial-delete check, so a
 *   200 with a per-key error fails the call that retention waits on;
 * - the witness deletes its probe even when its listing fails, and a failing
 *   delete is attached to the listing failure rather than replacing it.
 */
class DeletionWiringTest {
    /** An S3Client whose every call goes to [handler] by method name; anything unhandled fails the test. */
    private fun stub(handler: (String, Array<Any?>) -> Any?): S3Client =
        Proxy.newProxyInstance(S3Client::class.java.classLoader, arrayOf(S3Client::class.java)) { _, method, args ->
            when (method.name) {
                "close" -> Unit
                "serviceName" -> "s3"
                else -> handler(method.name, args ?: emptyArray()) ?: error("unexpected S3 call ${method.name}")
            }
        } as S3Client

    private val config =
        S3BlobStoreConfig(endpoint = "http://unused", accessKey = "a", secretKey = "b", bucket = "bucket", createBucket = false)

    @Test
    fun `deletePrefix fails when DeleteObjects answers 200 with a per-key error`() {
        val calls = mutableListOf<String>()
        val s3 =
            stub { name, args ->
                calls += name
                when (name) {
                    "listObjectsV2" -> {
                        (args[0] as ListObjectsV2Request).prefix() shouldBe "ws/inbox/"
                        ListObjectsV2Response
                            .builder()
                            .contents(S3Object.builder().key("ws/inbox/m/raw.eml").build())
                            .build()
                    }

                    "deleteObjects" -> {
                        (args[0] as DeleteObjectsRequest).delete().objects().map { it.key() } shouldBe listOf("ws/inbox/m/raw.eml")
                        DeleteObjectsResponse
                            .builder()
                            .errors(
                                S3Error
                                    .builder()
                                    .key("ws/inbox/m/raw.eml")
                                    .code("InternalError")
                                    .build(),
                            ).build()
                    }

                    else -> {
                        null
                    }
                }
            }
        val store = S3BlobStore(config, BlobStoreMetrics.NOOP, s3)

        assertThrows<PartialDeleteException> { store.deletePrefix("ws/inbox/") }
        calls shouldBe listOf("listObjectsV2", "deleteObjects")
    }

    @Test
    fun `deletePrefix succeeds when every listed key was deleted`() {
        val s3 =
            stub { name, _ ->
                when (name) {
                    "listObjectsV2" -> {
                        ListObjectsV2Response.builder().contents(S3Object.builder().key("ws/inbox/m/raw.eml").build()).build()
                    }

                    "deleteObjects" -> {
                        DeleteObjectsResponse.builder().build()
                    }

                    else -> {
                        null
                    }
                }
            }

        S3BlobStore(config, BlobStoreMetrics.NOOP, s3).deletePrefix("ws/inbox/")
    }

    @Test
    fun `the witness deletes its probe even when the listing fails, and keeps the listing failure primary`() {
        val deleted = mutableListOf<String>()
        val s3 =
            stub { name, args ->
                when (name) {
                    "putObject" -> {
                        PutObjectResponse.builder().build()
                    }

                    "listObjectsV2" -> {
                        error("listing failed")
                    }

                    "deleteObject" -> {
                        deleted += (args[0] as DeleteObjectRequest).key()
                        error("delete failed too")
                    }

                    else -> {
                        null
                    }
                }
            }
        val inspection = S3StorageInspection(s3, "bucket")

        val failure = assertThrows<IllegalStateException> { inspection.witness("_probe/node/1") }

        failure.message shouldBe "listing failed"
        deleted shouldBe listOf("_probe/node/1")
        failure.suppressed
            .single()
            .shouldBeInstanceOf<IllegalStateException>()
            .message shouldBe "delete failed too"
    }

    @Test
    fun `a successful witness deletes its probe too`() {
        val deleted = mutableListOf<String>()
        val s3 =
            stub { name, args ->
                when (name) {
                    "putObject" -> {
                        PutObjectResponse.builder().build()
                    }

                    "listObjectsV2" -> {
                        ListObjectsV2Response.builder().contents(S3Object.builder().key("_probe/node/2").build()).build()
                    }

                    "deleteObject" -> {
                        deleted += (args[0] as DeleteObjectRequest).key()
                        DeleteObjectResponse.builder().build()
                    }

                    else -> {
                        null
                    }
                }
            }

        S3StorageInspection(s3, "bucket").witness("_probe/node/2") shouldBe true
        deleted shouldBe listOf("_probe/node/2")
    }
}
