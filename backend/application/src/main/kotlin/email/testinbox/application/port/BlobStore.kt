package email.testinbox.application.port

import java.time.Instant

/**
 * S3-compatible object storage for raw MIME and attachment bytes (ADR-005).
 * Keys are per-message ownership keys (data-ownership.md) — never
 * content-addressed.
 */
interface BlobStore {
    fun put(key: String, bytes: ByteArray, contentType: String)

    fun get(key: String): ByteArray?

    fun delete(key: String)

    /** Deletes every object under [prefix]; idempotent. */
    fun deletePrefix(prefix: String)

    /** Keys under [prefix] last modified before [olderThan] — orphan sweep support. */
    fun listKeysOlderThan(prefix: String, olderThan: Instant): List<String>
}
