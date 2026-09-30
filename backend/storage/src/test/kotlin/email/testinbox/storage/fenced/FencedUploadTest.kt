package email.testinbox.storage.fenced

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.port.UploadRefusal
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ADR-035 §5 and §18 gates 1–3, against the PINNED MinIO (the same digest the
 * data tier runs): the presigned fence (key, length, create-only, start
 * deadline, explicit clock), one attempt, the definitive/ambiguous split,
 * `T_put` with an RST abort observed on the wire, and URL redaction.
 *
 * Probes E3, E5, E8, E9 and I1–I6 (`docs/adr/0035-benchmark/minio-probes`)
 * are the reference. These tests re-prove them through the application's own
 * presigner and client.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FencedUploadTest {
    private val minio =
        MinIOContainer(
            DockerImageName
                .parse(
                    "ghcr.io/yannisyoussef/testinbox-mirror/minio@sha256:bbac678936882e4033b6068efd0a19d103b219002d979087f7c5bd330172e00d",
                ).asCompatibleSubstituteFor("minio/minio"),
        ).withUserName(ACCESS)
            .withPassword(SECRET)
            .also { it.start() }

    private val endpoint = URI.create(minio.s3URL)
    private val presigner = SigV4Presigner(ACCESS, SECRET, "us-east-1")
    private val s3 =
        S3Client
            .builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS, SECRET)))
            .forcePathStyle(true)
            .build()
            .also { it.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build()) }

    private lateinit var proxy: TcpFaultProxy

    @BeforeEach
    fun freshProxy() {
        if (::proxy.isInitialized) proxy.close()
        proxy = TcpFaultProxy(InetSocketAddress(endpoint.host, endpoint.port))
    }

    @AfterAll
    fun tearDown() {
        proxy.close()
        s3.close()
        minio.stop()
    }

    private fun uploader(
        via: URI = endpoint,
        tPut: Duration = Duration.ofSeconds(30),
        connector: FencedUploader.SocketConnector = FencedUploader.SocketConnector.DIRECT,
    ) = FencedUploader(via, BUCKET, presigner, tPut, Duration.ofSeconds(2), connector)

    private fun viaProxy() = URI.create("http://${endpoint.host}:${proxy.port}")

    private fun key() = "${UUID.randomUUID()}/${UUID.randomUUID()}/${UUID.randomUUID()}/raw.eml"

    private fun upload(
        key: String,
        bytes: ByteArray,
        signedAt: Instant = Instant.now(),
        validFor: Duration = Duration.ofSeconds(120),
    ) = ReservedUpload(key, bytes, signedAt, validFor)

    /** Strict existence: 200 is present, 404 is absent, anything else fails the test. */
    private fun exists(key: String): Boolean =
        try {
            s3.headObject(
                HeadObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build(),
            )
            true
        } catch (e: NoSuchKeyException) {
            false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw e
        }

    private fun content(key: String): ByteArray =
        s3
            .getObjectAsBytes(
                GetObjectRequest
                    .builder()
                    .bucket(BUCKET)
                    .key(key)
                    .build(),
            ).asByteArray()

    /** Sends an arbitrary request to a presigned URL: the server, not the client, must refuse it. */
    private fun rawPut(
        url: URI,
        headers: Map<String, String>,
        body: ByteArray,
        chunked: Boolean = false,
        path: String = url.rawPath,
    ): Pair<Int, String?> =
        Socket(url.host, url.port).use { socket ->
            val out = socket.getOutputStream()
            val head = StringBuilder("PUT $path?${url.rawQuery} HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (chunked) head.append("Transfer-Encoding: chunked\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            if (chunked) {
                out.write("${body.size.toString(16)}\r\n".toByteArray())
                out.write(body)
                out.write("\r\n0\r\n\r\n".toByteArray())
            } else {
                out.write(body)
            }
            out.flush()
            val response = String(socket.getInputStream().readAllBytes())
            val status = response.substringAfter(' ').take(3).toInt()
            status to Regex("<Code>([^<]+)</Code>").find(response)?.groupValues?.get(1)
        }

    // --- the fence, on the pinned MinIO (gate 1) ----------------------------------------------

    @Test
    fun `a correct fenced upload stores exactly the reserved bytes`() {
        val key = key()
        val bytes = ByteArray(10_000) { it.toByte() }

        uploader().put(upload(key, bytes)) shouldBe UploadOutcome.Stored

        content(key).toList() shouldBe bytes.toList()
    }

    @Test
    fun `the URL signs host, content-length and if-none-match, and its date is the given clock`() {
        val signedAt = Instant.parse("2026-09-29T12:34:56Z")
        val url = presigner.presignPut(endpoint, BUCKET, key(), 42, signedAt, Duration.ofSeconds(120))

        val query = url.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        query.getValue("X-Amz-SignedHeaders") shouldBe "content-length%3Bhost%3Bif-none-match"
        query.getValue("X-Amz-Date") shouldBe "20260929T123456Z" // the database t0, not the node clock
        query.getValue("X-Amz-Expires") shouldBe "120"
    }

    @Test
    fun `the server refuses any other length, a chunked body, a dropped create-only header and another key`() {
        val n = 1_500
        val key = key()
        val url = presigner.presignPut(endpoint, BUCKET, key, n.toLong(), Instant.now(), Duration.ofSeconds(120))
        val signed = mapOf("Content-Length" to "$n", "If-None-Match" to "*")

        rawPut(url, signed + ("Content-Length" to "${n + 1}"), ByteArray(n + 1)).first shouldBe 403 // E9b
        rawPut(url, signed + ("Content-Length" to "${n - 1}"), ByteArray(n - 1)).first shouldBe 403 // E9c
        rawPut(url, mapOf("If-None-Match" to "*"), ByteArray(n), chunked = true).first shouldBe 411 // E9d
        rawPut(url, mapOf("Content-Length" to "$n"), ByteArray(n)).first shouldBe 400 // I5: create-only cannot be dropped
        val other = "/$BUCKET/${key()}"
        rawPut(url, signed, ByteArray(n), path = other).first shouldBe 403 // the path is signed
        exists(key) shouldBe false
        exists(other.removePrefix("/$BUCKET/")) shouldBe false
    }

    @Test
    fun `a replay is refused 412 and the stored object is unchanged`() {
        val key = key()
        uploader().put(upload(key, ByteArray(100) { 1 })) shouldBe UploadOutcome.Stored

        uploader().put(upload(key, ByteArray(100) { 2 })) shouldBe UploadOutcome.Refused(UploadRefusal.ALREADY_EXISTS)

        content(key).toSet() shouldBe setOf<Byte>(1) // I2/I6
    }

    @Test
    fun `an upload that starts after the deadline is refused, whoever signed it`() {
        // E8: signed 300 s ago, valid for 120 s. Definitive: no object.
        val key = key()

        uploader().put(upload(key, ByteArray(64), signedAt = Instant.now().minusSeconds(300))) shouldBe
            UploadOutcome.Refused(UploadRefusal.DENIED)

        exists(key) shouldBe false
    }

    // --- one attempt, and the definitive/ambiguous split -----------------------------------------

    @Test
    fun `a response swallowed after the full body is ambiguous, and the object exists anyway (E5)`() {
        proxy.mode = TcpFaultProxy.Mode.SWALLOW_RESPONSE
        val key = key()

        uploader(viaProxy(), tPut = Duration.ofSeconds(2)).put(upload(key, ByteArray(4_096))) shouldBe
            UploadOutcome.Ambiguous(AmbiguityKind.TIMEOUT)

        // "The client did not see success" is not "no object". This is why
        // an ambiguous reservation is never released inline.
        exists(key) shouldBe true
        proxy.connections.get() shouldBe 1 // one attempt: no retry, no second request
    }

    @Test
    fun `a reset after the full body is ambiguous, with exactly one server-side request`() {
        proxy.mode = TcpFaultProxy.Mode.RESET_AFTER_BODY

        uploader(viaProxy()).put(upload(key(), ByteArray(2_048))) shouldBe UploadOutcome.Ambiguous(AmbiguityKind.CONNECTION_LOST)

        proxy.connections.get() shouldBe 1
    }

    @Test
    fun `canned storage answers are classified exactly as ADR-035 lists them`() {
        proxy.mode = TcpFaultProxy.Mode.RESPOND

        fun answer(
            status: String,
            code: String? = null,
        ): UploadOutcome {
            val body = code?.let { "<?xml version=\"1.0\"?><Error><Code>$it</Code></Error>" }.orEmpty()
            proxy.canned = "HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            return uploader(viaProxy()).put(upload(key(), ByteArray(10)))
        }

        answer("400 Bad Request", "XMinioAdminBucketQuotaExceeded") shouldBe UploadOutcome.Refused(UploadRefusal.QUOTA) // Q1
        answer("403 Forbidden", "AccessDenied") shouldBe UploadOutcome.Refused(UploadRefusal.DENIED)
        answer("403 Forbidden", "SignatureDoesNotMatch") shouldBe UploadOutcome.Refused(UploadRefusal.DENIED)
        answer("411 Length Required", "MissingContentLength") shouldBe UploadOutcome.Refused(UploadRefusal.LENGTH_REQUIRED)
        answer("412 Precondition Failed", "PreconditionFailed") shouldBe UploadOutcome.Refused(UploadRefusal.ALREADY_EXISTS)
        answer("500 Internal Server Error", "InternalError") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
        answer("503 Service Unavailable", "SlowDown") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
        answer("403 Forbidden", "RequestTimeTooSkewed") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE)
        answer("400 Bad Request", "InvalidArgument") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE)
        answer("404 Not Found", "NoSuchBucket") shouldBe UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE)
        answer("200 OK") shouldBe UploadOutcome.Stored
        proxy.connections.get() shouldBe 11 // exactly one request per upload
    }

    @Test
    fun `no connection at all is definitive, because nothing reached storage`() {
        val closedPort = java.net.ServerSocket(0).use { it.localPort }

        uploader(URI.create("http://127.0.0.1:$closedPort")).put(upload(key(), ByteArray(10))) shouldBe UploadOutcome.NotStarted
    }

    // --- T_put and the RST (gate 2) --------------------------------------------------------------

    @Test
    fun `a stalled upload is aborted at T_put with an RST, and no object ever appears`() {
        proxy.mode = TcpFaultProxy.Mode.STALL
        proxy.stallAfterBytes = 1_024 // the request head and a sliver of the body reach storage
        val key = key()
        val started = System.nanoTime()

        uploader(viaProxy(), tPut = Duration.ofSeconds(1)).put(upload(key, ByteArray(256 * 1024))) shouldBe
            UploadOutcome.Ambiguous(AmbiguityKind.TIMEOUT)

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        (elapsed < Duration.ofSeconds(5)) shouldBe true // a total wall-clock bound, not an inactivity timeout
        proxy.clientEnds.poll(10, TimeUnit.SECONDS) shouldBe "RST" // observed on the wire: not a FIN
        // Storage saw an incomplete body; nothing commits (E2), now or later.
        repeat(3) {
            exists(key) shouldBe false
            Thread.sleep(500)
        }
    }

    @Test
    fun `a completed upload ends with a normal close, so the RST is specific to the abort`() {
        proxy.mode = TcpFaultProxy.Mode.PASS

        uploader(viaProxy()).put(upload(key(), ByteArray(100))) shouldBe UploadOutcome.Stored

        proxy.clientEnds.poll(10, TimeUnit.SECONDS) shouldBe "FIN"
    }

    // --- redaction (gate 3) ---------------------------------------------------------------------

    @Test
    fun `no presigned URL credential reaches a log line or an outcome, even when the failure quotes it`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(FencedUploader::class.java) as Logger
        logger.addAppender(appender)
        try {
            val leakingUrl = { presigner.presignPut(endpoint, BUCKET, "leak", 10, Instant.now(), Duration.ofSeconds(120)).toString() }
            // A connection failure whose message quotes the full URL.
            val refuses =
                FencedUploader.SocketConnector { _, _ -> throw IOException("connect failed for PUT ${leakingUrl()}") }
            // A write failure, after connecting, whose message quotes the full URL.
            val breaksMidRequest =
                FencedUploader.SocketConnector { address, timeout ->
                    val real = FencedUploader.SocketConnector.DIRECT.connect(address, timeout)
                    object : Socket() {
                        override fun getOutputStream(): OutputStream =
                            object : OutputStream() {
                                override fun write(b: Int): Unit = throw IOException("broken pipe writing ${leakingUrl()}")
                            }

                        override fun getInputStream() = real.getInputStream()

                        override fun close() = real.close()

                        override fun setSoLinger(
                            on: Boolean,
                            linger: Int,
                        ) = real.setSoLinger(on, linger)
                    }
                }

            val outcomes =
                listOf(
                    uploader(connector = refuses).put(upload(key(), ByteArray(10))),
                    uploader(connector = breaksMidRequest).put(upload(key(), ByteArray(10))),
                    uploader(
                        URI.create("http://127.0.0.1:${java.net.ServerSocket(0).use { it.localPort }}"),
                    ).put(upload(key(), ByteArray(10))),
                )

            outcomes.map { it.definitive } shouldBe listOf(true, false, true)
            val logged = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
            logged shouldContain "fenced_upload" // the failures WERE logged
            logged shouldContain "X-Amz-Signature=<redacted>" // and the quoted URL was scrubbed
            Regex("X-Amz-Signature=[0-9a-f]{16,}").findAll(logged).toList().shouldBeEmpty()
            logged shouldNotContain "X-Amz-Credential=$ACCESS"
            appender.list.mapNotNull { it.throwableProxy }.shouldBeEmpty() // stack traces could carry messages: none are logged
            outcomes.joinToString().shouldNotContain("X-Amz")
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `scrubbing removes every SigV4 query parameter value`() {
        val url = presigner.presignPut(endpoint, BUCKET, "k", 1, Instant.now(), Duration.ofSeconds(120)).toString()

        val scrubbed = Redaction.scrub("failed: $url")

        scrubbed shouldNotContain Regex("X-Amz-Signature=[0-9a-f]").pattern
        Regex("X-Amz-[A-Za-z-]+=([^&]*)").findAll(scrubbed).map { it.groupValues[1] }.toSet() shouldBe setOf("<redacted>")
        Redaction.describe(URI.create(url)) shouldBe "${endpoint.scheme}://${endpoint.rawAuthority}/$BUCKET/k"
    }

    private companion object {
        const val ACCESS = "testinbox"
        const val SECRET = "testinbox123"
        const val BUCKET = "fenced"
    }
}
