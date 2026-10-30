package email.testinbox.api.account

import email.testinbox.application.identity.EmailLoginCode
import email.testinbox.domain.identity.ContactEmail
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.mail.MailException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmtpLoginDeliveryTest {
    private fun properties() = LoginSmtpProperties("relay.example.invalid", 587, "fixture", "fixture", "login@example.invalid")

    @Test
    fun `a server without STARTTLS receives no authentication recipient or code`() {
        val transcript = CopyOnWriteArrayList<String>()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 5000
            val accepted =
                executor.submit {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write("220 fixture ESMTP\r\n")
                        writer.flush()
                        while (true) {
                            val line = reader.readLine() ?: break
                            transcript.add(line)
                            if (line.startsWith("EHLO")) {
                                writer.write("250-fixture\r\n250 AUTH LOGIN PLAIN\r\n")
                            } else if (line == "QUIT") {
                                writer.write("221 bye\r\n")
                                writer.flush()
                                break
                            } else {
                                writer.write("500 refused\r\n")
                            }
                            writer.flush()
                        }
                    }
                }
            try {
                // Change only the test transport destination; all production TLS/auth
                // configuration is retained from the same factory the live adapter uses.
                val sender =
                    SmtpLoginDelivery.configuredSender(properties()).apply {
                        host = listener.inetAddress.hostAddress
                        port = listener.localPort
                    }
                val code = EmailLoginCode.generate()
                shouldThrow<MailException> {
                    SmtpLoginDelivery(properties(), sender).send(checkNotNull(ContactEmail.parse("recipient@example.invalid")), code)
                }
                accepted.get(10, TimeUnit.SECONDS)
                transcript.any { it.startsWith("EHLO") } shouldBe true
                transcript.none { line -> listOf("AUTH", "MAIL", "RCPT", "DATA").any { line.startsWith(it) } } shouldBe true
                transcript.none { it.contains(code.value) } shouldBe true
            } finally {
                accepted.cancel(true)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `incomplete or plaintext mail configuration is refused without exposing credentials`() {
        shouldThrow<IllegalArgumentException> { SmtpLoginDelivery.validate(LoginSmtpProperties()) }
        shouldThrow<IllegalArgumentException> {
            SmtpLoginDelivery.validate(LoginSmtpProperties("relay.example.invalid", 25, "fixture", "fixture", "login@example.invalid"))
        }
        shouldThrow<IllegalArgumentException> {
            SmtpLoginDelivery.validate(
                LoginSmtpProperties("relay.example.invalid", 587, "fixture", "fixture", "bad\r\nBcc: person@example.invalid"),
            )
        }
        properties().toString().contains("fixture") shouldBe false
        EmailLoginProperties(true, "never-log-this-key", properties()).toString().contains("never-log-this-key") shouldBe false
    }
}
