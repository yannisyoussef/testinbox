package email.testinbox.ingestion.smtp

import email.testinbox.application.TestInboxConfig
import email.testinbox.application.usecase.ReceiveInboundMessage
import email.testinbox.ingestion.config.IngestionProperties
import java.io.InputStream
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.subethamail.smtp.MessageContext
import org.subethamail.smtp.MessageHandler
import org.subethamail.smtp.RejectException
import org.subethamail.smtp.server.SMTPServer

const val LOCAL_SMTP_PROVIDER = "local-smtp"

/**
 * Local SMTP inbound adapter (ADR-003/ADR-004): terminates the SMTP
 * session and invokes the shared ReceiveInboundMessage use case. Protocol
 * rules (ADR-025 / inbound-mail-flow.md):
 *  - RCPT TO accepted only for syntactically valid addresses on our mail
 *    domain; recipient EXISTENCE is never checked at RCPT time;
 *  - uniform 250 after DATA whether or not the recipient resolves — no
 *    enumeration oracle, unknown-recipient content never persisted;
 *  - infrastructure failure => 451 soft failure so the sender MTA retries.
 * Each completed DATA transaction is a distinct delivery: the local
 * provider supplies NO providerMessageId, so nothing is ever deduplicated
 * (ADR-019).
 */
@Component
class SmtpGateway(
    private val receive: ReceiveInboundMessage,
    private val properties: IngestionProperties,
    private val config: TestInboxConfig,
) : SmartLifecycle {
    @Volatile private var server: SMTPServer? = null

    override fun start() {
        val smtpServer =
            SMTPServer.port(properties.smtp.port)
                .messageHandlerFactory { context: MessageContext -> Handler() }
                .maxMessageSize(config.maxRawSizeBytes.toInt())
                .softwareName("TestInbox")
                .build()
        smtpServer.start()
        server = smtpServer
        log.info("SMTP gateway listening on port {}", properties.smtp.port)
    }

    override fun stop() {
        server?.stop()
        server = null
    }

    override fun isRunning(): Boolean = server != null

    private inner class Handler : MessageHandler {
        private var envelopeFrom: String? = null
        private val recipients = mutableListOf<String>()

        override fun from(from: String) {
            envelopeFrom = from.ifBlank { null }
        }

        override fun recipient(recipient: String) {
            val normalized = recipient.trim().lowercase()
            val domain = normalized.substringAfterLast('@', missingDelimiterValue = "")
            if (domain != config.mailDomain.lowercase() || normalized.substringBefore('@').isEmpty()) {
                // Syntax/domain-level rejection only — never an existence check (ADR-025).
                throw RejectException(553, "Requested action not taken: mailbox name not allowed")
            }
            recipients += normalized
        }

        override fun data(data: InputStream): String? {
            val raw = readBounded(data)
            for (recipient in recipients) {
                try {
                    receive.execute(
                        ReceiveInboundMessage.Command(
                            recipientAddress = recipient,
                            envelopeFrom = envelopeFrom,
                            envelopeTo = recipient,
                            raw = raw,
                            provider = LOCAL_SMTP_PROVIDER,
                            providerMessageId = null,
                        ),
                    )
                } catch (e: Exception) {
                    // Persistence/storage unavailable: soft-fail so the sender retries (failure-modes.md).
                    log.error("inbound delivery failed for hashed recipient; soft-failing", e)
                    throw RejectException(451, "Requested action aborted: local error in processing")
                }
            }
            // Uniform success regardless of recipient resolution (ADR-025).
            return null
        }

        override fun done() {}

        private fun readBounded(data: InputStream): ByteArray {
            val bytes = data.readNBytes(config.maxRawSizeBytes.toInt() + 1)
            if (bytes.size > config.maxRawSizeBytes) {
                throw RejectException(552, "Requested mail action aborted: exceeded storage allocation")
            }
            return bytes
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SmtpGateway::class.java)
    }
}
