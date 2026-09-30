package email.testinbox.application.port

import java.time.Instant

/**
 * S3-compatible object storage for raw MIME and attachment bytes (ADR-005).
 * Keys are per-message ownership keys (data-ownership.md) — never
 * content-addressed.
 *
 * There is NO unfenced write (ADR-035 §5). The only way to create a payload
 * object is [putReserved]: a presigned, create-only, size-bound PUT that a
 * live reservation authorized. An ArchUnit rule keeps it that way.
 */
interface BlobStore {
    /** One attempt, never retried; see [UploadOutcome] for what each result proves. */
    fun putReserved(upload: ReservedUpload): UploadOutcome

    fun get(key: String): ByteArray?

    fun delete(key: String)

    /** Deletes every object under [prefix]; idempotent. */
    fun deletePrefix(prefix: String)

    /** Keys under [prefix] last modified before [olderThan] — orphan sweep support. */
    fun listKeysOlderThan(
        prefix: String,
        olderThan: Instant,
    ): List<String>
}
