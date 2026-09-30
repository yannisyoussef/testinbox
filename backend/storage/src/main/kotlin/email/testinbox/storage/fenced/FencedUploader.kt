package email.testinbox.storage.fenced

import email.testinbox.application.port.AmbiguityKind
import email.testinbox.application.port.ReservedUpload
import email.testinbox.application.port.UploadOutcome
import email.testinbox.application.port.UploadRefusal
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The ADR-035 §5 upload client: one presigned, create-only, size-bound,
 * single-part PUT per exact key, attempted exactly once.
 *
 * It is a deliberately small HTTP/1.1 client, not a general-purpose one,
 * because the properties the physical bound rests on have to be provable on
 * the wire:
 * - **One attempt.** There is no retry anywhere: no client retry, no redirect
 *   following, no second connection. A retry would be a second server-side
 *   request per slot, which H does not cover.
 * - **No pipelining.** Nothing is written after the body. Pipelined bytes
 *   would suppress the RST's cancellation (MinIO `server.go:691`).
 * - **Total wall-clock `T_put`.** A watchdog, not a socket inactivity timeout,
 *   ends the attempt, because a trickling peer defeats inactivity timeouts.
 * - **Abort by RST.** At `T_put` the socket is closed with `SO_LINGER 0`, so
 *   the kernel resets the connection and discards any buffered body tail. A
 *   FIN would let that tail still be delivered after the deadline.
 * - **No URL in any log or message.** The presigned URL is a bearer
 *   credential. Only its scheme, authority and path are ever named, and every
 *   underlying error message is scrubbed of SigV4 parameters.
 */
class FencedUploader(
    private val endpoint: URI,
    private val bucket: String,
    private val presigner: SigV4Presigner,
    private val tPut: Duration = DEFAULT_T_PUT,
    private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val connector: SocketConnector = SocketConnector.DIRECT,
) {
    init {
        require(!tPut.isNegative && !tPut.isZero) { "T_put must be positive" }
        require(endpoint.scheme == "http" || endpoint.scheme == "https") { "the storage endpoint must be http or https" }
    }

    /** Opens the TCP connection. A test seam for fault injection; production connects directly. */
    fun interface SocketConnector {
        fun connect(
            address: InetSocketAddress,
            timeout: Duration,
        ): Socket

        companion object {
            val DIRECT =
                SocketConnector { address, timeout ->
                    Socket().apply { connect(address, timeout.toMillis().toInt().coerceAtLeast(1)) }
                }
        }
    }

    fun put(upload: ReservedUpload): UploadOutcome {
        val url =
            presigner.presignPut(endpoint, bucket, upload.key, upload.bytes.size.toLong(), upload.signedAt, upload.validFor)
        val outcome = attempt(url, upload.bytes)
        if (outcome != UploadOutcome.Stored) {
            log.warn("fenced_upload outcome={} target={} bytes={}", outcome, Redaction.describe(url), upload.bytes.size)
        }
        return outcome
    }

    private fun attempt(
        url: URI,
        body: ByteArray,
    ): UploadOutcome {
        val port =
            if (url.port != -1) {
                url.port
            } else if (url.scheme == "https") {
                443
            } else {
                80
            }
        val aborted = AtomicBoolean(false)
        var plain: Socket? = null
        val watchdog =
            WATCHDOG.schedule({
                aborted.set(true)
                plain?.let(::abort)
            }, tPut.toNanos(), TimeUnit.NANOSECONDS)
        var requestStarted = false
        var completed = false
        try {
            val socket =
                try {
                    connector.connect(InetSocketAddress(url.host, port), minOf(connectTimeout, tPut))
                } catch (e: IOException) {
                    // No connection: no byte of the request can have reached storage.
                    log.warn("fenced_upload not_started target={} cause={}", Redaction.describe(url), Redaction.scrub(e.toString()))
                    return UploadOutcome.NotStarted
                }
            plain = socket
            if (aborted.get()) abort(socket)
            socket.soTimeout = tPut.toMillis().toInt().coerceAtLeast(1) // a backstop only; the watchdog is the bound
            socket.tcpNoDelay = true
            val stream = if (url.scheme == "https") tls(socket, url.host, port) else socket
            val out = stream.getOutputStream()
            requestStarted = true
            out.write(head(url, body.size.toLong()))
            out.write(body)
            out.flush()
            // Nothing else is ever written on this connection: no pipelining.
            val response = readResponse(BufferedInputStream(stream.getInputStream()))
            completed = true
            return classify(response)
        } catch (e: IOException) {
            if (!requestStarted) {
                log.warn("fenced_upload not_started target={} cause={}", Redaction.describe(url), Redaction.scrub(e.toString()))
                return UploadOutcome.NotStarted
            }
            val kind = if (aborted.get() || e is SocketTimeoutException) AmbiguityKind.TIMEOUT else AmbiguityKind.CONNECTION_LOST
            log.warn("fenced_upload ambiguous kind={} target={} cause={}", kind, Redaction.describe(url), Redaction.scrub(e.toString()))
            return UploadOutcome.Ambiguous(kind)
        } finally {
            watchdog.cancel(false)
            plain?.let { if (completed && !aborted.get()) closeQuietly(it) else abort(it) }
        }
    }

    private fun head(
        url: URI,
        contentLength: Long,
    ): ByteArray {
        val host = if (url.port == -1) url.host else "${url.host}:${url.port}"
        // Exactly the signed headers, plus Connection: close (never signed).
        // No Expect: 100-continue, no Transfer-Encoding.
        return (
            "PUT ${url.rawPath}?${url.rawQuery} HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Content-Length: $contentLength\r\n" +
                "If-None-Match: *\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        ).toByteArray(Charsets.US_ASCII)
    }

    private fun tls(
        socket: Socket,
        host: String,
        port: Int,
    ): Socket =
        (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true).also {
            (it as SSLSocket).startHandshake()
        }

    internal data class Response(
        val status: Int,
        val errorCode: String?,
    )

    private fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("connection ended before a status line")
        val status =
            Regex("""^HTTP/1\.[01] (\d{3})""")
                .find(statusLine)
                ?.groupValues
                ?.get(1)
                ?.toInt()
                ?: throw IOException("malformed status line")
        var contentLength: Int? = null
        var chunked = false
        while (true) {
            val line = readLine(input) ?: throw IOException("connection ended inside the headers")
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (name == "content-length") contentLength = value.toIntOrNull()
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }
        // The status line decides the outcome. The body only names an error
        // code, so it is read best-effort, and bounded.
        val body =
            runCatching {
                when {
                    chunked -> readChunked(input)
                    contentLength != null -> input.readNBytes(minOf(contentLength, MAX_ERROR_BODY))
                    else -> input.readNBytes(MAX_ERROR_BODY)
                }
            }.getOrDefault(ByteArray(0))
        val code = Regex("<Code>([^<]{1,100})</Code>").find(String(body, Charsets.UTF_8))?.groupValues?.get(1)
        return Response(status, code)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < MAX_ERROR_BODY) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(input.readNBytes(minOf(size, MAX_ERROR_BODY - out.size())))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val b = input.read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
        throw IOException("response line too long")
    }

    companion object {
        /** ADR-035 §5: the total wall-clock bound of one upload. */
        val DEFAULT_T_PUT: Duration = Duration.ofSeconds(30)
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        private const val MAX_ERROR_BODY = 64 * 1024
        private const val MAX_LINE = 8 * 1024

        /**
         * The definitive outcomes of ADR-035 §5, and nothing else. Any status
         * or error code it does not list is ambiguous.
         */
        internal fun classify(response: Response): UploadOutcome =
            when {
                response.status in 200..299 -> {
                    UploadOutcome.Stored
                }

                response.status == 403 && response.errorCode in setOf("AccessDenied", "SignatureDoesNotMatch") -> {
                    UploadOutcome.Refused(UploadRefusal.DENIED)
                }

                response.status == 411 -> {
                    UploadOutcome.Refused(UploadRefusal.LENGTH_REQUIRED)
                }

                response.status == 412 -> {
                    UploadOutcome.Refused(UploadRefusal.ALREADY_EXISTS)
                }

                response.status == 400 && response.errorCode == "XMinioAdminBucketQuotaExceeded" -> {
                    UploadOutcome.Refused(UploadRefusal.QUOTA)
                }

                response.status in 500..599 -> {
                    UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
                }

                else -> {
                    UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE)
                }
            }

        /** `SO_LINGER 0` then close: the kernel sends RST and discards unsent data. */
        fun abort(socket: Socket) {
            try {
                socket.setSoLinger(true, 0)
            } catch (_: IOException) {
                // Already closed: nothing left to reset.
            }
            closeQuietly(socket)
        }

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Nothing useful to do.
            }
        }

        private val WATCHDOG: ScheduledExecutorService =
            (
                Executors.newScheduledThreadPool(1) { r ->
                    Thread(r, "fenced-upload-watchdog").apply { isDaemon = true }
                } as ScheduledThreadPoolExecutor
            ).apply { removeOnCancelPolicy = true }

        private val log = LoggerFactory.getLogger(FencedUploader::class.java)
    }
}
