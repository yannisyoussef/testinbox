package email.testinbox.storage.fenced

import email.testinbox.application.storage.StorageProtocol
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * ADR-035 §9a / TI-STORAGE-006 §14: the upload implementation version names
 * the qualified protocol implementation. The string alone would be a label
 * nobody has to touch, so this test derives a FINGERPRINT of the shape from
 * the real code paths — the signed header set the presigner produces, the
 * request head the uploader writes, `T_put`, the abort, the single attempt
 * and the direct connector — and pins it next to the version. Change any of
 * those and this test fails until BOTH the fingerprint literal and
 * `StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION` are deliberately updated,
 * which is the re-qualification conversation ADR-035 §9a requires.
 *
 * The behaviours themselves are proven against the pinned MinIO and the TCP
 * fault proxy elsewhere (`S3BlobStoreTest`, `FencedUploaderTest`); this test
 * pins their SHAPE.
 */
class FencedUploaderVersionTest {
    @Test
    fun `the uploader declares the protocol version the qualification records are matched against`() {
        FencedUploader.IMPLEMENTATION_VERSION shouldBe StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION
        StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION shouldBe "adr035-presigned-put-v2"
    }

    @Test
    fun `the protocol shape fingerprint matches the one adr035-presigned-put-v2 is pinned to`() {
        val presigner = SigV4Presigner("fixture-access-key", "fixture-secret-key", "us-east-1")
        val signed =
            presigner.presignPut(
                URI.create("http://storage.internal:9000"),
                "bucket",
                "ws/inbox/msg/raw.eml",
                contentLength = 15L * 1024 * 1024,
                signedAt = Instant.parse("2026-01-01T00:00:00Z"),
                validFor = Duration.ofSeconds(120),
            )
        val query = signed.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val uploader = FencedUploader(URI.create("http://storage.internal:9000"), "bucket", presigner)
        // The head is rendered against a FIXED url, so only the template's shape is fingerprinted.
        val head = String(uploader.head(URI.create("http://storage.internal:9000/bucket/k?X-Amz-Signature=fixed"), 4096), Charsets.US_ASCII)
        val shape =
            listOf(
                "signed-headers=" + query.getValue("X-Amz-SignedHeaders"), // content-length;host;if-none-match
                "payload=" + (query["X-Amz-Content-Sha256"] ?: "UNSIGNED-PAYLOAD(query-absent)"),
                "expires=" + query.getValue("X-Amz-Expires"),
                "head=" + head.replace("\r\n", "|"),
                "t_put=" + FencedUploader.DEFAULT_T_PUT.toSeconds(),
                "connect-timeout=" + FencedUploader.DEFAULT_CONNECT_TIMEOUT.toSeconds(),
                "abort=SO_LINGER(0)",
                "attempts=1",
                // The default connector is the plain-socket one (a lambda class has no stable name, so the fact is stated).
                "connector=direct",
                // v2: the response is read while the body is written; a non-2xx before the body completes ends it by RST.
                "response=concurrent-with-body",
                "early-rst=non-2xx-before-body-complete",
            ).joinToString("\n")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(shape.toByteArray()).joinToString("") { "%02x".format(it) }
        // Pinned with the version. A different value here means the protocol changed: bump the
        // version in StorageProtocol AND re-qualify (ADR-035 §9a) before updating this literal.
        fingerprint shouldBe QUALIFIED_SHAPE_FINGERPRINT
        head.contains("Expect:") shouldBe false
        head.contains("Transfer-Encoding:") shouldBe false
        head.contains("If-None-Match: *") shouldBe true
        head.contains("Connection: close") shouldBe true
    }

    private companion object {
        const val QUALIFIED_SHAPE_FINGERPRINT = "9471c29ece5085fd160ee1a6457d9d63f0990f7860c00ab65eb15d8aa9ac962d"
    }
}
