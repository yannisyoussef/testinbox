package email.testinbox.storage

import email.testinbox.application.port.IncompleteUpload
import email.testinbox.application.port.ServerTime
import email.testinbox.application.port.StorageInspection
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The inspection side of ADR-035 cleanup, verification and the orphan sweep.
 *
 * Every existence check is strict. A listing either names the exact key or it
 * does not, and any failure propagates, so an error can never be mistaken for
 * "absent" (§9a, qualification rules).
 */
class S3StorageInspection(
    private val s3: S3Client,
    private val bucket: String,
) : StorageInspection {
    override fun objectExists(key: String): Boolean =
        s3
            .listObjectsV2(
                ListObjectsV2Request
                    .builder()
                    .bucket(bucket)
                    .prefix(key)
                    .maxKeys(1000)
                    .build(),
            ).contents()
            .any { it.key() == key }

    override fun incompleteUploadExists(key: String): Boolean =
        // The EXACT key as the prefix (probe M3). MinIO does not list an open
        // upload under its parent prefix (M2), so a parent-prefix proof would
        // pass vacuously.
        s3
            .listMultipartUploads(
                ListMultipartUploadsRequest
                    .builder()
                    .bucket(bucket)
                    .prefix(key)
                    .build(),
            ).uploads()
            .any { it.key() == key }

    override fun deleteObject(key: String) {
        s3.deleteObject(
            DeleteObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .build(),
        )
    }

    override fun incompleteUploads(): List<IncompleteUpload> {
        val result = mutableListOf<IncompleteUpload>()
        var keyMarker: String? = null
        var uploadMarker: String? = null
        do {
            val page =
                s3.listMultipartUploads(
                    ListMultipartUploadsRequest
                        .builder()
                        .bucket(bucket)
                        .keyMarker(keyMarker)
                        .uploadIdMarker(uploadMarker)
                        .build(),
                )
            page.uploads().forEach { result += IncompleteUpload(it.key(), it.uploadId(), it.initiated()) }
            keyMarker = page.nextKeyMarker()
            uploadMarker = page.nextUploadIdMarker()
        } while (page.isTruncated == true)
        return result
    }

    override fun abortIncompleteUpload(upload: IncompleteUpload) {
        s3.abortMultipartUpload(
            AbortMultipartUploadRequest
                .builder()
                .bucket(bucket)
                .key(upload.key)
                .uploadId(upload.uploadId)
                .build(),
        )
    }

    /**
     * ADR-035 §7 storage liveness witness. It proves that storage is not
     * stalled: a commit can still complete and be listed. It does NOT prove
     * that earlier commits have drained. The probe object is infrastructure
     * under `_probe/`, outside payload accounting, and it is the only write in
     * this codebase that bypasses the presigned fence.
     */
    override fun witness(probeKey: String): Boolean {
        require(probeKey.startsWith(PROBE_PREFIX)) { "a witness writes only under $PROBE_PREFIX" }
        s3.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(probeKey)
                .build(),
            RequestBody.fromBytes(ByteArray(0)), // the ADR-035 §8 zero-byte probe
        )
        // Deleted whatever the listing does: a probe left behind after a
        // failed listing would be allocated bytes no ledger row describes
        // (filesystem-containment contract §5.3, TI-STORAGE-006E).
        var failure: Throwable? = null
        try {
            return objectExists(probeKey)
        } catch (e: RuntimeException) {
            failure = e
            throw e
        } finally {
            try {
                deleteObject(probeKey)
            } catch (e: RuntimeException) {
                // The listing's own failure is the one to report; the leftover probe is the sweep's.
                failure?.addSuppressed(e) ?: throw e
            }
        }
    }

    override fun serverTime(): ServerTime {
        val started = System.nanoTime()
        val response = s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        val roundTrip = Duration.ofNanos(System.nanoTime() - started)
        val date =
            response
                .sdkHttpResponse()
                .firstMatchingHeader("Date")
                .orElseThrow { IllegalStateException("storage answered without a Date header") }
        return ServerTime(ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(), roundTrip)
    }

    override fun listedPayloadBytes(): Long {
        var total = 0L
        var continuation: String? = null
        do {
            val page =
                s3.listObjectsV2(
                    ListObjectsV2Request
                        .builder()
                        .bucket(bucket)
                        .continuationToken(continuation)
                        .build(),
                )
            total += page.contents().filterNot { it.key().startsWith(PROBE_PREFIX) }.sumOf { it.size() }
            continuation = page.nextContinuationToken()
        } while (continuation != null)
        return total
    }

    companion object {
        const val PROBE_PREFIX = "_probe/"

        fun isPayload(key: String): Boolean = !key.startsWith(PROBE_PREFIX)
    }
}
