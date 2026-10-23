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
import java.util.concurrent.atomic.AtomicReference
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
    /** TLS for an `https` endpoint. The hostname IS verified (see [tls]); tests supply their own trust. */
    private val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
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
                    val socket = Socket()
                    try {
                        socket.apply { connect(address, timeout.toMillis().toInt().coerceAtLeast(1)) }
                    } catch (e: IOException) {
                        socket.close()
                        throw e
                    }
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
        // Shared with the watchdog thread: both sides must see each other's
        // writes, or the T_put abort could miss the socket entirely.
        val plainRef = AtomicReference<Socket?>(null)
        val watchdog =
            WATCHDOG.schedule({
                aborted.set(true)
                plainRef.get()?.let(::abort)
            }, tPut.toNanos(), TimeUnit.NANOSECONDS)
        var requestStarted = false
        var completed = false
        var definitive = false
        var reader: java.util.concurrent.Future<Response>? = null
        try {
            val socket =
                try {
                    connector.connect(InetSocketAddress(url.host, port), minOf(connectTimeout, tPut))
                } catch (e: IOException) {
                    // No connection: no byte of the request can have reached storage.
                    log.warn("fenced_upload not_started target={} cause={}", Redaction.describe(url), Redaction.scrub(e.toString()))
                    return UploadOutcome.NotStarted
                }
            plainRef.set(socket)
            if (aborted.get()) abort(socket)
            socket.soTimeout = tPut.toMillis().toInt().coerceAtLeast(1) // a backstop only; the watchdog is the bound
            socket.tcpNoDelay = true
            val connection = if (url.scheme == "https") tls(socket, url.host, port) else socket
            // The response is read WHILE the body is written. Storage may answer from the
            // headers alone (a missed deadline, a replay, the quota, a full disk) and stop
            // reading; the write then blocks until storage resets the connection, and the
            // reset discards an answer still unread in our buffer (Ops E8: most out-of-space
            // failures arrived that way). Read concurrently, the answer is taken as it
            // arrives, and an answer before the body is complete ends the attempt by RST:
            // storage has decided, and no tail of ours may still be delivered.
            val bodyWritten = AtomicBoolean(false)
            reader = startReader(connection, bodyWritten, plainRef)
            val out = connection.getOutputStream()
            requestStarted = true
            out.write(head(url, body.size.toLong()))
            out.write(body)
            out.flush()
            bodyWritten.set(true)
            // Nothing else is ever written on this connection: no pipelining.
            val response = awaitResponse(reader, tPut)
            completed = true
            return classify(response).also { definitive = it.definitive }
        } catch (e: IOException) {
            return failed(url, e, requestStarted, aborted.get(), reader)
        } finally {
            watchdog.cancel(false)
            // A normal close only after a definitive answer. Anything else, a
            // non-definitive response included, is reset, so no body tail can
            // still be delivered.
            plainRef.get()?.let { if (completed && definitive && !aborted.get()) closeQuietly(it) else abort(it) }
        }
    }

    /**
     * Reads the response on its own thread while the body is written. A non-2xx answer
     * before the body is complete is storage's decision: the socket is reset at once, so
     * the blocked writer is freed and no tail of ours is delivered. A 2xx only follows
     * the whole body, so it never resets.
     */
    private fun startReader(
        connection: Socket,
        bodyWritten: AtomicBoolean,
        plainRef: AtomicReference<Socket?>,
    ): java.util.concurrent.Future<Response> =
        RESPONSE_READERS.submit(
            java.util.concurrent.Callable {
                readResponse(BufferedInputStream(connection.getInputStream())).also {
                    if (!bodyWritten.get() && it.status !in 200..299) plainRef.get()?.let(::abort)
                }
            },
        )

    /** The outcome of an attempt whose request failed after the connection was made. */
    private fun failed(
        url: URI,
        e: IOException,
        requestStarted: Boolean,
        aborted: Boolean,
        reader: java.util.concurrent.Future<Response>?,
    ): UploadOutcome {
        if (!requestStarted) {
            log.warn("fenced_upload not_started target={} cause={}", Redaction.describe(url), Redaction.scrub(e.toString()))
            return UploadOutcome.NotStarted
        }
        if (!aborted && e !is SocketTimeoutException) {
            // Storage may have answered from the headers alone and stopped
            // reading, which fails our write. The concurrent reader may hold its
            // answer, classified as it is: a definitive one is used as such, and an
            // ambiguous one keeps its kind (STORAGE_FULL trips its own breaker,
            // a 5xx stays SERVER_ERROR). With no answer it is CONNECTION_LOST.
            reader?.let { earlyAnswer(it) }?.let { return it }
        }
        val kind = if (aborted || e is SocketTimeoutException) AmbiguityKind.TIMEOUT else AmbiguityKind.CONNECTION_LOST
        log.warn("fenced_upload ambiguous kind={} target={} cause={}", kind, Redaction.describe(url), Redaction.scrub(e.toString()))
        return UploadOutcome.Ambiguous(kind)
    }

    private fun earlyAnswer(reader: java.util.concurrent.Future<Response>): UploadOutcome? =
        try {
            classify(awaitResponse(reader, EARLY_ANSWER_WAIT))
        } catch (_: IOException) {
            null
        }

    /** The concurrent reader's response within [limit]; its failure, or no answer in time, is an [IOException]. */
    private fun awaitResponse(
        reader: java.util.concurrent.Future<Response>,
        limit: Duration,
    ): Response =
        try {
            reader.get(limit.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? IOException) ?: IOException("response reader failed", e.cause)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw SocketTimeoutException("no response within $limit")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while awaiting the response", e)
        }

    internal fun head(
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

    /**
     * TLS over the already-connected socket, so an abort can still reset the
     * plain one. The certificate must name the endpoint's host. JSSE checks
     * only the chain unless endpoint identification is switched on, and an
     * attacker on the path with any trusted certificate could otherwise read
     * every message and presigned URL, or fake a `200`.
     */
    private fun tls(
        socket: Socket,
        host: String,
        port: Int,
    ): Socket =
        (tlsFactory.createSocket(socket, host, port, true) as SSLSocket).also { ssl ->
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
        }

    internal data class Response(
        val status: Int,
        val errorCode: String?,
        /** MinIO's `<Message>`, bounded. Read only to recognise ENOSPC; never logged. */
        val errorMessage: String? = null,
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
        val text = String(body, Charsets.UTF_8)
        val code = Regex("<Code>([^<]{1,100})</Code>").find(text)?.groupValues?.get(1)
        val message = Regex("<Message>([^<]{1,8000})</Message>").find(text)?.groupValues?.get(1)
        return Response(status, code, message)
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
        /**
         * ADR-035 §9a: the protocol implementation this class IS. Bump
         * `StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION` whenever this class
         * changes presigning, signed headers, attempts, `T_put`, the RST, the
         * connection path or anything else a qualification could depend on.
         */
        const val IMPLEMENTATION_VERSION = email.testinbox.application.storage.StorageProtocol.UPLOAD_IMPLEMENTATION_VERSION

        /** ADR-035 §5: the total wall-clock bound of one upload. */
        val DEFAULT_T_PUT: Duration = Duration.ofSeconds(30)
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** Reads each attempt's response concurrently with its body write. Daemon threads: never block shutdown. */
        private val RESPONSE_READERS: java.util.concurrent.ExecutorService =
            Executors.newCachedThreadPool { r -> Thread(r, "fenced-upload-response").apply { isDaemon = true } }

        /** How long an early answer is waited for after a failed write. Well inside `T_put`; the watchdog still bounds it. */
        private val EARLY_ANSWER_WAIT: Duration = Duration.ofSeconds(1)
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

                isStorageFull(response) -> {
                    UploadOutcome.Ambiguous(AmbiguityKind.STORAGE_FULL)
                }

                response.status in 500..599 -> {
                    UploadOutcome.Ambiguous(AmbiguityKind.SERVER_ERROR)
                }

                else -> {
                    UploadOutcome.Ambiguous(AmbiguityKind.UNEXPECTED_RESPONSE)
                }
            }

        /**
         * The pinned MinIO's two answers for a full filesystem (containment
         * contract §8): `507 XMinioStorageFull`, or a `500` whose message names
         * ENOSPC. NEVER by status alone: any other `5xx` is a server error.
         * Still ambiguous for the reservation; only the breaker kind differs.
         */
        internal fun isStorageFull(response: Response): Boolean =
            // 507 Insufficient Storage is the full-storage status. With MinIO's code, or with
            // no code at all (a lost or truncated body), it is storage-full: the conservative
            // reading, since a SERVER_ERROR trial is a zero-byte probe a full filesystem
            // passes. Another code under 507 is not assumed.
            (response.status == 507 && (response.errorCode == null || response.errorCode == "XMinioStorageFull")) ||
                (
                    response.status == 500 &&
                        response.errorMessage?.contains("no space left on device", ignoreCase = true) == true
                )

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
