package email.testinbox.application.port

import email.testinbox.domain.message.EmailHeader
import email.testinbox.domain.message.EmailLink

data class MimeAttachment(
    val fileName: String?,
    val contentType: String?,
    val bytes: ByteArray,
)

data class ParsedMime(
    val fromAddress: String?,
    val fromHeader: String?,
    val toHeader: String?,
    val subject: String?,
    val textBody: String?,
    val htmlBody: String?,
    val headers: List<EmailHeader>,
    val links: List<EmailLink>,
    val attachments: List<MimeAttachment>,
)

sealed interface MimeParseResult {
    data class Parsed(
        val content: ParsedMime,
    ) : MimeParseResult

    /** Classified failure — the message is persisted as ParseFailed, raw MIME retained (ADR-005). */
    data class Failed(
        val reason: String,
    ) : MimeParseResult
}

/**
 * Total MIME parsing: any byte sequence yields either Parsed or Failed —
 * implementations must never let an exception escape or hang on hostile
 * input.
 */
interface MimeParser {
    fun parse(raw: ByteArray): MimeParseResult
}
