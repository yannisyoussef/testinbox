package email.testinbox.storage

import email.testinbox.application.port.BlobOperation
import email.testinbox.application.port.BlobOutcome
import email.testinbox.application.port.BlobStore
import email.testinbox.application.port.BlobStoreMetrics
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.StorageInspection
import email.testinbox.application.port.UploadOutcome
import email.testinbox.storage.fenced.FencedUploader
import email.testinbox.storage.fenced.SigV4Presigner
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
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
import java.net.URI
import java.time.Duration
import java.time.Instant

data class S3BlobStoreConfig(
    val endpoint: String,
    val region: String = "us-east-1",
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    /** Create the bucket at startup when missing — local/MinIO convenience. */
    val createBucket: Boolean = true,
    /** `T_put` (ADR-035 §5). Tests shorten it; the deployables use the default. */
    val uploadTimeout: Duration = FencedUploader.DEFAULT_T_PUT,
)

/**
 * S3-compatible blob adapter (ADR-005): MinIO locally, any S3 provider in
 * production. Path-style access for MinIO compatibility.
 */
class S3BlobStore(
    private val config: S3BlobStoreConfig,
    private val metrics: BlobStoreMetrics = BlobStoreMetrics.NOOP,
) : BlobStore,
    AutoCloseable {
    private val s3: S3Client =
        S3Client
            .builder()
            .endpointOverride(URI.create(config.endpoint))
            .region(Region.of(config.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey, config.secretKey),
                ),
            ).forcePathStyle(true)
            .build()

    init {
        if (config.createBucket) ensureBucket()
    }

    /**
     * Check-then-act against a shared bucket, so it must tolerate losing the
     * race. The API and ingestion deployables start simultaneously and both
     * run this: both see no bucket, both create it, and the loser gets a 409.
     * Treating that as a failure made the loser refuse to start — an outage
     * caused entirely by the convenience feature, and one that reproduces only
     * under concurrent startup.
     *
     * Both 409s mean the bucket exists, which is the postcondition this
     * function is for. `BucketAlreadyExists` (someone else owns the name)
     * still propagates: continuing would mean writing raw MIME into a bucket
     * this deployment does not control.
     */
    private fun ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(config.bucket).build())
        } catch (_: NoSuchBucketException) {
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build())
            } catch (_: BucketAlreadyOwnedByYouException) {
                // Another process of this deployment created it first.
            }
        }
    }

    private val uploader =
        FencedUploader(
            endpoint = URI.create(config.endpoint),
            bucket = config.bucket,
            presigner = SigV4Presigner(config.accessKey, config.secretKey, config.region),
            tPut = config.uploadTimeout,
        )

    /**
     * The only payload write (ADR-035 §5): presigned with the reservation's
     * database clock, create-only, size-bound, attempted once, aborted by RST
     * at `T_put`. Its outcome is never retried or reinterpreted here.
     */
    override fun putReserved(upload: ReservedUpload): UploadOutcome {
        val started = System.nanoTime()
        val outcome = uploader.put(upload)
        metrics.operationCompleted(
            BlobOperation.PUT,
            Duration.ofNanos(System.nanoTime() - started),
            if (outcome == UploadOutcome.Stored) BlobOutcome.SUCCESS else BlobOutcome.FAILURE,
        )
        return outcome
    }

    /** Cleanup, verification and orphan-sweep operations on the same bucket and client. */
    fun inspection(): StorageInspection = S3StorageInspection(s3, config.bucket)

    override fun get(key: String): ByteArray? =
        // A miss is reported as NOT_FOUND, not success: a raw MIME object that
        // is absent when /raw asks for it (ADR-005) is precisely the failure
        // this metric would be consulted for, and success would hide it.
        timed(BlobOperation.GET, missIsNotFound = true) {
            getOrNull(key)
        }

    private fun getOrNull(key: String): ByteArray? =
        try {
            s3
                .getObjectAsBytes(
                    GetObjectRequest
                        .builder()
                        .bucket(config.bucket)
                        .key(key)
                        .build(),
                ).asByteArray()
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun delete(key: String) =
        timed(BlobOperation.DELETE) {
            s3.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(config.bucket)
                    .key(key)
                    .build(),
            )
            Unit
        }

    override fun deletePrefix(prefix: String) =
        timed(BlobOperation.DELETE_PREFIX) {
            var continuation: String? = null
            do {
                val listing =
                    s3.listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(config.bucket)
                            .prefix(prefix)
                            .continuationToken(continuation)
                            .build(),
                    )
                val keys = listing.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
                if (keys.isNotEmpty()) {
                    val response =
                        s3.deleteObjects(
                            DeleteObjectsRequest
                                .builder()
                                .bucket(config.bucket)
                                .delete(Delete.builder().objects(keys).build())
                                .build(),
                        )
                    // DeleteObjects is per-key best effort: a 200 can carry <Error>
                    // entries for keys that stayed. Retention deletes rows only after
                    // this returned, so a key that stayed must FAIL here, or it would
                    // be an object no row, no reservation and no debt row describes
                    // (filesystem-containment contract §2.2 (f), §4.6).
                    failOnPartialDelete(response)
                }
                continuation = listing.nextContinuationToken()
            } while (continuation != null)
        }

    override fun listKeysOlderThan(
        prefix: String,
        olderThan: Instant,
    ): List<String> =
        timed(BlobOperation.LIST) {
            val result = mutableListOf<String>()
            var continuation: String? = null
            do {
                val listing =
                    s3.listObjectsV2(
                        ListObjectsV2Request
                            .builder()
                            .bucket(config.bucket)
                            .prefix(prefix)
                            .continuationToken(continuation)
                            .build(),
                    )
                listing
                    .contents()
                    .filter { it.lastModified().isBefore(olderThan) }
                    .forEach { result += it.key() }
                continuation = listing.nextContinuationToken()
            } while (continuation != null)
            result
        }

    override fun close() {
        s3.close()
    }

    companion object {
        /** Throws when [response] reports any key that was not deleted. Codes only: a key names a tenant's prefix. */
        internal fun failOnPartialDelete(response: software.amazon.awssdk.services.s3.model.DeleteObjectsResponse) {
            val errors = response.errors()
            if (errors.isNullOrEmpty()) return
            val codes = errors.map { it.code() ?: "unknown" }.groupingBy { it }.eachCount()
            throw PartialDeleteException("${errors.size} of the listed objects were not deleted: $codes")
        }
    }

    /**
     * Times one call and records it under a fixed operation label. Object
     * storage is on the critical path of every inbound delivery — raw bytes are
     * written before the row (ADR-005) — so its latency is the first thing to
     * look at when ingestion slows and nothing else changed.
     *
     * The outcome tag is a boolean and the key is never a label: keys are
     * caller-derived and therefore unbounded.
     */
    private fun <T> timed(
        operation: BlobOperation,
        missIsNotFound: Boolean = false,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        var outcome = BlobOutcome.FAILURE
        try {
            val result = block()
            outcome = if (missIsNotFound && result == null) BlobOutcome.NOT_FOUND else BlobOutcome.SUCCESS
            return result
        } finally {
            metrics.operationCompleted(operation, Duration.ofNanos(System.nanoTime() - startedAt), outcome)
        }
    }
}

/** `DeleteObjects` answered 200 but left objects behind; the caller must not delete their rows. */
class PartialDeleteException(
    message: String,
) : RuntimeException(message)
