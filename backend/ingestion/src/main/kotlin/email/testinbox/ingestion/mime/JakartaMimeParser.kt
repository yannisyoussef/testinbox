package email.testinbox.ingestion.mime

import email.testinbox.application.port.MimeAttachment
import email.testinbox.application.port.MimeParseResult
import email.testinbox.application.port.MimeParser
import email.testinbox.application.port.ParsedMime
import email.testinbox.domain.message.EmailHeader
import email.testinbox.domain.message.EmailLink
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.util.Properties

/**
 * Total MIME parsing over Jakarta Mail (email-mime-expert rules): any byte
 * sequence yields Parsed or a classified Failed — exceptions never escape.
 * Hostile-input bounds: nesting depth and part-count limits (MIME bombs),
 * link-count cap. Fidelity first: headers are kept as received; parsed
 * fields are a convenience layer over /raw.
 */
class JakartaMimeParser(
    private val maxDepth: Int = 20,
    private val maxParts: Int = 500,
    private val maxLinks: Int = 500,
) : MimeParser {
    private val session: Session = Session.getInstance(Properties())

    override fun parse(raw: ByteArray): MimeParseResult =
        try {
            doParse(raw)
        } catch (e: ParseAbort) {
            MimeParseResult.Failed(e.message ?: "parse aborted")
        } catch (e: Exception) {
            MimeParseResult.Failed("${e.javaClass.simpleName}: ${e.message ?: "unparseable MIME"}")
        }

    private class ParseAbort(
        message: String,
    ) : RuntimeException(message)

    private class Collector {
        var text: String? = null
        var html: String? = null
        val attachments = mutableListOf<MimeAttachment>()
        var parts = 0
    }

    private fun doParse(raw: ByteArray): MimeParseResult {
        val mime = MimeMessage(session, ByteArrayInputStream(raw))
        val headers =
            buildList {
                val enumeration = mime.allHeaders
                while (enumeration.hasMoreElements()) {
                    val header = enumeration.nextElement()
                    add(EmailHeader(header.name, header.value ?: ""))
                }
            }
        // Jakarta Mail is lenient about garbage: require at least one
        // plausible RFC 5322 header (field name without whitespace/control chars).
        val plausibleHeader = headers.any { HEADER_NAME.matches(it.name) }
        if (!plausibleHeader) throw ParseAbort("no RFC 5322 headers found")

        val collector = Collector()
        walk(mime, collector, depth = 0)

        val fromAddresses = runCatching { mime.from }.getOrNull()
        val fromAddress = (fromAddresses?.firstOrNull() as? InternetAddress)?.address
        val fromHeader = mime.getHeader("From", ", ")
        val toHeader = mime.getHeader("To", ", ")
        // RFC 2047 encoded-word decoding for Unicode subjects.
        val subject = runCatching { mime.subject }.getOrNull()

        return MimeParseResult.Parsed(
            ParsedMime(
                fromAddress = fromAddress,
                fromHeader = fromHeader,
                toHeader = toHeader,
                subject = subject,
                textBody = collector.text,
                htmlBody = collector.html,
                headers = headers,
                links = extractLinks(collector.html, collector.text),
                attachments = collector.attachments,
            ),
        )
    }

    private fun walk(
        part: Part,
        collector: Collector,
        depth: Int,
    ) {
        if (depth > maxDepth) throw ParseAbort("MIME nesting exceeds depth limit of $maxDepth")
        if (++collector.parts > maxParts) throw ParseAbort("MIME part count exceeds limit of $maxParts")

        val isMultipart = runCatching { part.isMimeType("multipart/*") }.getOrDefault(false)
        if (isMultipart) {
            val multipart =
                runCatching { part.content as? MimeMultipart }.getOrNull()
                    ?: throw ParseAbort("unreadable multipart content")
            for (i in 0 until multipart.count) {
                walk(multipart.getBodyPart(i), collector, depth + 1)
            }
            return
        }

        val disposition = runCatching { part.disposition }.getOrNull()
        val fileName = runCatching { part.fileName?.let(MimeUtility::decodeText) }.getOrNull()
        val isAttachment = Part.ATTACHMENT.equals(disposition, ignoreCase = true) || fileName != null

        if (!isAttachment && runCatching { part.isMimeType("text/plain") }.getOrDefault(false)) {
            if (collector.text == null) collector.text = readText(part)
            return
        }
        if (!isAttachment && runCatching { part.isMimeType("text/html") }.getOrDefault(false)) {
            if (collector.html == null) collector.html = readText(part)
            return
        }

        val bytes =
            runCatching { part.inputStream.readAllBytes() }
                .getOrElse { throw ParseAbort("unreadable body part: ${it.message}") }
        collector.attachments +=
            MimeAttachment(
                fileName = fileName,
                contentType = runCatching { ContentType(part.contentType).baseType }.getOrNull(),
                bytes = bytes,
            )
    }

    private fun readText(part: Part): String =
        runCatching { part.content as? String }.getOrNull()
            // Fallback for broken/unknown charsets: keep the bytes readable rather than failing the message.
            ?: String(part.inputStream.readAllBytes(), Charsets.ISO_8859_1)

    private fun extractLinks(
        html: String?,
        text: String?,
    ): List<EmailLink> {
        val links = LinkedHashMap<String, EmailLink>()
        var htmlVisibleText: String? = null
        if (html != null) {
            val document = Jsoup.parse(html)
            for (anchor in document.select("a[href]")) {
                if (links.size >= maxLinks) break
                val href = anchor.attr("href").trim()
                if (href.isEmpty()) continue
                links.putIfAbsent(href, EmailLink(href, anchor.text().ifBlank { null }))
            }
            htmlVisibleText = document.text()
        }
        for (candidate in listOfNotNull(text, htmlVisibleText)) {
            for (match in URL_PATTERN.findAll(candidate)) {
                if (links.size >= maxLinks) break
                val href = match.value.trimEnd('.', ',', ';', ')')
                links.putIfAbsent(href, EmailLink(href, null))
            }
        }
        return links.values.toList()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://[^\s<>"'\[\]]+""")
        val HEADER_NAME = Regex("""[!-9;-~]+""")
    }
}
