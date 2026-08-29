package email.testinbox.storage

import email.testinbox.application.port.BlobStore
import java.net.URI
import java.time.Instant
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest

data class S3BlobStoreConfig(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    /** Create the bucket at startup when missing — local/MinIO convenience. */
    val createBucket: Boolean = true,
)

/**
 * S3-compatible blob adapter (ADR-005): MinIO locally, any S3 provider in
 * production. Path-style access for MinIO compatibility.
 */
class S3BlobStore(private val config: S3BlobStoreConfig) : BlobStore, AutoCloseable {
    private val s3: S3Client =
        S3Client.builder()
            .endpointOverride(URI.create(config.endpoint))
            .region(Region.of(config.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey, config.secretKey),
                ),
            )
            .forcePathStyle(true)
            .build()

    init {
        if (config.createBucket) ensureBucket()
    }

    private fun ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
        } catch (_: NoSuchBucketException) {
            s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
        }
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        s3.putObject(
            PutObjectRequest.builder().bucket(config.bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): ByteArray? =
        try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket).key(key).build()).asByteArray()
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun delete(key: String) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
    }

    override fun deletePrefix(prefix: String) {
        var continuation: String? = null
        do {
            val listing =
                s3.listObjectsV2(
                    ListObjectsV2Request.builder()
                        .bucket(config.bucket)
                        .prefix(prefix)
                        .continuationToken(continuation)
                        .build(),
                )
            val keys = listing.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
            if (keys.isNotEmpty()) {
                s3.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(config.bucket)
                        .delete(Delete.builder().objects(keys).build())
                        .build(),
                )
            }
            continuation = listing.nextContinuationToken()
        } while (continuation != null)
    }

    override fun listKeysOlderThan(prefix: String, olderThan: Instant): List<String> {
        val result = mutableListOf<String>()
        var continuation: String? = null
        do {
            val listing =
                s3.listObjectsV2(
                    ListObjectsV2Request.builder()
                        .bucket(config.bucket)
                        .prefix(prefix)
                        .continuationToken(continuation)
                        .build(),
                )
            listing.contents()
                .filter { it.lastModified().isBefore(olderThan) }
                .forEach { result += it.key() }
            continuation = listing.nextContinuationToken()
        } while (continuation != null)
        return result
    }

    override fun close() {
        s3.close()
    }
}
