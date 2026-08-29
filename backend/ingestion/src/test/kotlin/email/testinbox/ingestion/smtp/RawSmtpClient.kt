package email.testinbox.ingestion.smtp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket

/**
 * Minimal raw SMTP client so tests exercise the true wire protocol —
 * including malformed payloads a well-behaved mail library would refuse to
 * send.
 */
class RawSmtpClient(
    host: String,
    port: Int,
) : AutoCloseable {
    private val socket = Socket(host, port).apply { soTimeout = 15_000 }
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
    private val output: OutputStream = socket.getOutputStream()

    init {
        expect(220)
        command("EHLO test-client")
    }

    fun command(line: String): Reply {
        output.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return readReply()
    }

    /** Sends MAIL FROM/RCPT TO/DATA with [raw] as the message body; returns the final DATA reply. */
    fun send(
        from: String,
        recipients: List<String>,
        raw: ByteArray,
    ): Reply {
        expectOk(command("MAIL FROM:<$from>"))
        for (recipient in recipients) {
            expectOk(command("RCPT TO:<$recipient>"))
        }
        val dataReply = command("DATA")
        check(dataReply.code == 354) { "expected 354, got $dataReply" }
        output.write(dotStuff(raw))
        output.write("\r\n.\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return readReply()
    }

    /** RCPT TO probe returning the raw reply (for rejection tests). */
    fun rcptProbe(
        from: String,
        recipient: String,
    ): Reply {
        expectOk(command("MAIL FROM:<$from>"))
        val reply = command("RCPT TO:<$recipient>")
        command("RSET")
        return reply
    }

    fun quit() {
        runCatching { command("QUIT") }
    }

    override fun close() {
        quit()
        socket.close()
    }

    data class Reply(
        val code: Int,
        val text: String,
    )

    private fun readReply(): Reply {
        var line: String
        val text = StringBuilder()
        do {
            line = reader.readLine() ?: error("connection closed by server")
            text.appendLine(line)
        } while (line.length >= 4 && line[3] == '-')
        return Reply(line.take(3).toInt(), text.toString().trim())
    }

    private fun expect(code: Int) {
        val reply = readReply()
        check(reply.code == code) { "expected $code, got $reply" }
    }

    private fun expectOk(reply: Reply) {
        check(reply.code == 250) { "expected 250, got $reply" }
    }

    private fun dotStuff(raw: ByteArray): ByteArray {
        val text = String(raw, Charsets.ISO_8859_1)
        val stuffed = text.lineSequence().joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }
        return stuffed.toByteArray(Charsets.ISO_8859_1)
    }
}
