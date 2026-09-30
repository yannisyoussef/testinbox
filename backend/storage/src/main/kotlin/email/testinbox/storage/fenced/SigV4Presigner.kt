package email.testinbox.storage.fenced

import software.amazon.awssdk.http.SdkHttpMethod
import software.amazon.awssdk.http.SdkHttpRequest
import software.amazon.awssdk.http.auth.aws.signer.AwsV4FamilyHttpSigner
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The ADR-035 §5 presigner: a SigV4 query-string ("presigned") PUT whose
 * signature covers, besides the host and path:
 *
 * - `content-length`: a request of any other size is `403` (probes E9b/E9c),
 *   and a chunked one is `411` (E9d);
 * - `if-none-match: *`: the URL can only create, so a replay is `412` (I2/I4),
 *   and dropping the header is refused (I5).
 *
 * The signing clock is explicit. It is T1's database `t0`, never the node's
 * clock (probes E6/E8 show why a fast signing clock would stretch the fence).
 * The payload is unsigned (`UNSIGNED-PAYLOAD`), as in the probes. The
 * length, not a hash, binds the size.
 */
class SigV4Presigner(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
) {
    private val signer = AwsV4HttpSigner.create()

    fun presignPut(
        endpoint: URI,
        bucket: String,
        key: String,
        contentLength: Long,
        signedAt: Instant,
        validFor: Duration,
    ): URI {
        require(contentLength >= 0) { "content length must not be negative" }
        require(!validFor.isNegative && !validFor.isZero) { "a presigned URL must be valid for a positive duration" }
        val request =
            SdkHttpRequest
                .builder()
                .method(SdkHttpMethod.PUT)
                .uri(URI.create(endpoint.toString().trimEnd('/') + "/" + bucket + "/" + encodeKey(key)))
                .putHeader(CONTENT_LENGTH, contentLength.toString())
                .putHeader(IF_NONE_MATCH, "*")
                .build()
        val signed =
            signer.sign { r ->
                r
                    .request(request)
                    .identity(AwsCredentialsIdentity.create(accessKey, secretKey))
                    .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "s3")
                    .putProperty(AwsV4HttpSigner.REGION_NAME, region)
                    .putProperty(AwsV4HttpSigner.AUTH_LOCATION, AwsV4FamilyHttpSigner.AuthLocation.QUERY_STRING)
                    .putProperty(AwsV4HttpSigner.EXPIRATION_DURATION, validFor)
                    .putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
                    .putProperty(AwsV4HttpSigner.DOUBLE_URL_ENCODE, false)
                    .putProperty(AwsV4HttpSigner.NORMALIZE_PATH, false)
                    .putProperty(HttpSigner.SIGNING_CLOCK, Clock.fixed(signedAt, ZoneOffset.UTC))
            }
        val uri = signed.request().uri
        // The binding exists only if these headers are signed (probe E9e). The
        // SDK signs every header it is given; this asserts it did.
        val signedHeaders =
            uri.rawQuery
                .split('&')
                .firstOrNull { it.startsWith("X-Amz-SignedHeaders=") }
                .orEmpty()
        check(
            CONTENT_LENGTH in signedHeaders && IF_NONE_MATCH in signedHeaders,
        ) { "the presigned PUT does not bind its length and create-only" }
        return uri
    }

    companion object {
        const val CONTENT_LENGTH = "content-length"
        const val IF_NONE_MATCH = "if-none-match"

        /** S3 path encoding: every segment percent-encoded, `/` kept. Keys are `uuid/uuid/uuid/...` today. */
        fun encodeKey(key: String): String =
            key.split('/').joinToString("/") { segment ->
                java.net.URLEncoder
                    .encode(segment, Charsets.UTF_8)
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
            }
    }
}

/**
 * A presigned URL is a bearer credential: its query string carries the
 * signature. It must never reach a log line, an exception message, a metric or
 * a trace (ADR-035 §5).
 */
object Redaction {
    private val signatureParameters =
        Regex("""(?i)(X-Amz-(Signature|Credential|Security-Token|SignedHeaders|Date|Expires|Algorithm|Content-Sha256))=[^&\s"'<>]*""")

    /** [text] with every SigV4 query parameter value removed. */
    fun scrub(text: String?): String = text?.replace(signatureParameters, "$1=<redacted>").orEmpty()

    /** A URI safe to name: scheme, host, port and path. Never the query, never user-info. */
    fun describe(uri: URI): String = uri.scheme + "://" + uri.host + (if (uri.port != -1) ":" + uri.port else "") + uri.rawPath
}
