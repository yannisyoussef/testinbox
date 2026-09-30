package email.testinbox.storage.fenced

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A TCP proxy between the upload client and MinIO that injects the faults of
 * ADR-035 §17 (tests 21–23): it can stall a body, swallow the response to a
 * complete body, reset the client after the body, or answer with a canned
 * response instead of storage.
 *
 * It also observes how the CLIENT ended the connection. A read that fails
 * with "Connection reset" is an RST. A read that returns end-of-stream is a
 * FIN. That distinction is the whole point of the `T_put` gate.
 */
class TcpFaultProxy(
    private val target: InetSocketAddress,
) : AutoCloseable {
    enum class Mode {
        /** Forward everything both ways. */
        PASS,

        /** Forward the request head and [stallAfterBytes] bytes, then nothing more. Never answer. */
        STALL,

        /** Forward the whole request; discard the response, so the client never sees it (probe E5). */
        SWALLOW_RESPONSE,

        /** Forward the whole request; when storage answers, reset the client instead. */
        RESET_AFTER_BODY,

        /** Do not contact storage: read the request, answer with [canned]. */
        RESPOND,

        /**
         * Do not contact storage: read the request, then answer one byte of a
         * status line every [trickleEvery], never finishing it. The client's
         * reads keep succeeding, so no inactivity timeout can ever fire: only a
         * total wall-clock bound ends the attempt.
         */
        TRICKLE,

        /**
         * Do not contact storage, and do not read the client for
         * [backpressureHoldMillis]: a large body fills the TCP window and the
         * client's `write()` blocks. A read timeout never covers a blocked
         * write. Only closing the socket frees it.
         */
        BACKPRESSURE,

        /**
         * Do not contact storage: answer [canned] as soon as the request head
         * is read, close the write side, discard the body for
         * [earlyResetAfterMillis], then RESET the client, as Go's server does
         * with an unread body. The client's body write fails, though the
         * answer is already on its side of the connection.
         */
        EARLY_ANSWER,
    }

    @Volatile var earlyResetAfterMillis = 200L

    @Volatile var trickleEvery: java.time.Duration = java.time.Duration.ofMillis(100)

    @Volatile var backpressureHoldMillis = 4_000L

    @Volatile var mode = Mode.PASS

    @Volatile var stallAfterBytes = 512

    @Volatile var canned: String = ""

    /**
     * When the client aborts a STALL, leave storage's side open and silent
     * instead of resetting it, so only storage's own idle timeout can end the
     * request (§17 test 53, the pre-EOF control).
     */
    @Volatile var holdUpstreamOnAbort = false

    /** Storage-side connections held open by [holdUpstreamOnAbort], closed by [close]. */
    private val held = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    /** When (System.nanoTime) each storage-side connection ended: storage closed, reset or answered it. */
    val upstreamEnds = LinkedBlockingQueue<Long>()

    /** How many client connections were accepted: one attempt means exactly one. */
    val connections = AtomicInteger()

    /** How each client connection ended: `RST`, `FIN`, or `ANSWERED` (the proxy closed it). */
    val clientEnds = LinkedBlockingQueue<String>()

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    private val acceptor =
        thread(isDaemon = true, name = "fault-proxy-accept") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                connections.incrementAndGet()
                val snapshot = mode
                thread(isDaemon = true, name = "fault-proxy-conn") { handle(client, snapshot) }
            }
        }

    private fun handle(
        client: Socket,
        mode: Mode,
    ) {
        if (mode == Mode.RESPOND) return respond(client)
        if (mode == Mode.TRICKLE) return trickle(client)
        if (mode == Mode.EARLY_ANSWER) return answerEarly(client)
        if (mode == Mode.BACKPRESSURE) {
            Thread.sleep(backpressureHoldMillis)
            clientEnds += pumpClient(client.getInputStream()) { _, _, _ -> }
            runCatching { client.close() }
            return
        }
        val upstream = Socket().apply { connect(target, 5_000) }
        val upstreamDone = CountDownLatch(1)
        // storage → client
        thread(isDaemon = true, name = "fault-proxy-down") {
            try {
                val input = upstream.getInputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    when (mode) {
                        Mode.PASS -> {
                            client.getOutputStream().write(buffer, 0, n)
                        }

                        Mode.SWALLOW_RESPONSE -> {
                            Unit
                        }

                        // the client never sees it
                        Mode.RESET_AFTER_BODY -> {
                            abort(client)
                            break
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                if (mode == Mode.PASS) client.shutdownOutput()
            } catch (_: IOException) {
                // Either side went away.
            } finally {
                upstreamEnds += System.nanoTime()
                upstreamDone.countDown()
            }
        }
        // client → storage, and how the client ended.
        val end =
            pumpClient(client.getInputStream()) { bytes, n, total ->
                val forward =
                    if (mode == Mode.STALL) {
                        (stallAfterBytes - (total - n)).coerceIn(0L, n.toLong()).toInt()
                    } else {
                        n
                    }
                if (forward > 0) upstream.getOutputStream().write(bytes, 0, forward)
            }
        clientEnds += end
        if (mode == Mode.STALL && holdUpstreamOnAbort) {
            held += upstream
            runCatching { client.close() }
            return
        }
        if (end == "RST" || mode == Mode.STALL) {
            // Mirror an aborted client onto storage: the request dies there too.
            abort(upstream)
        } else {
            upstreamDone.await(10, TimeUnit.SECONDS)
            runCatching { upstream.close() }
        }
        runCatching { client.close() }
    }

    /** Reads the client until it ends; returns `RST` or `FIN`. */
    private fun pumpClient(
        input: InputStream,
        forward: (ByteArray, Int, Long) -> Unit,
    ): String {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        return try {
            while (true) {
                val n = input.read(buffer)
                if (n == -1) return "FIN"
                total += n
                runCatching { forward(buffer, n, total) }
            }
            @Suppress("UNREACHABLE_CODE")
            "FIN"
        } catch (e: SocketException) {
            if (e.message.orEmpty().contains("reset", ignoreCase = true)) "RST" else "CLOSED:${e.message}"
        } catch (e: IOException) {
            "CLOSED:${e.message}"
        }
    }

    private fun respond(client: Socket) {
        try {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) return
                head.append(b.toChar())
            }
            val length =
                Regex("(?i)content-length: *(\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong() ?: 0
            input.skipNBytes(length)
            client.getOutputStream().write(canned.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
            clientEnds += "ANSWERED"
        } catch (_: IOException) {
            clientEnds += "CLOSED"
        } finally {
            runCatching { client.close() }
        }
    }

    private fun answerEarly(client: Socket) {
        try {
            val input = client.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) return
                head.append(b.toChar())
            }
            client.getOutputStream().write(canned.toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()
            client.shutdownOutput()
            val until = System.nanoTime() + earlyResetAfterMillis * 1_000_000
            val buffer = ByteArray(16 * 1024)
            client.soTimeout = 50
            while (System.nanoTime() < until) {
                try {
                    if (input.read(buffer) == -1) break
                } catch (_: java.net.SocketTimeoutException) {
                    // keep discarding until the reset
                }
            }
            clientEnds += "ANSWERED_EARLY"
        } catch (_: IOException) {
            clientEnds += "CLOSED"
        } finally {
            abort(client)
        }
    }

    private fun trickle(client: Socket) {
        val done =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        thread(isDaemon = true, name = "fault-proxy-trickle") {
            // "HTTP/1.1 200" then spaces: a status line that never ends.
            val bytes = "HTTP/1.1 200".toByteArray(Charsets.US_ASCII)
            var i = 0
            try {
                while (!done.get()) {
                    client.getOutputStream().write(if (i < bytes.size) bytes[i].toInt() else ' '.code)
                    client.getOutputStream().flush()
                    i++
                    Thread.sleep(trickleEvery.toMillis())
                }
            } catch (_: IOException) {
                // The client went away.
            }
        }
        clientEnds += pumpClient(client.getInputStream()) { _, _, _ -> }
        done.set(true)
        runCatching { client.close() }
    }

    private fun abort(socket: Socket) {
        runCatching { socket.setSoLinger(true, 0) }
        runCatching { socket.close() }
    }

    override fun close() {
        held.forEach { runCatching { it.close() } }
        server.close()
        acceptor.join(1_000)
    }
}
