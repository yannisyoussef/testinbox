package email.testinbox.ingestion.mime

import email.testinbox.application.port.MimeParseResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class JakartaMimeParserTest {
    private val parser = JakartaMimeParser()

    private fun corpus(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/mime-corpus/$name")) { "missing corpus fixture $name" }
            .readAllBytes()

    private fun parsed(name: String) = parser.parse(corpus(name)).shouldBeInstanceOf<MimeParseResult.Parsed>().content

    @Test
    fun `simple text message - headers, bodies and text links`() {
        val content = parsed("simple-text.eml")
        content.fromAddress shouldBe "no-reply@example.com"
        content.fromHeader.shouldNotBeNull() shouldContain "SUT"
        content.subject shouldBe "Verify your email"
        content.textBody.shouldNotBeNull() shouldContain "verify your account"
        content.htmlBody shouldBe null
        content.links.map { it.href } shouldContain "https://app.example.com/verify?token=abc123"
        content.headers.map { it.name } shouldContain "Message-ID"
        content.attachments shouldBe emptyList()
    }

    @Test
    fun `multipart alternative captures both text and html plus anchor links`() {
        val content = parsed("multipart-alternative.eml")
        content.textBody.shouldNotBeNull() shouldContain "Plain text version"
        content.htmlBody.shouldNotBeNull() shouldContain "<b>report</b>"
        val link = content.links.first { it.href == "https://app.example.com/report/42" }
        link.text shouldBe "Open report"
    }

    @Test
    fun `multipart mixed extracts the attachment with metadata and bytes`() {
        val content = parsed("multipart-mixed-attachment.eml")
        content.textBody.shouldNotBeNull() shouldContain "invoice is attached"
        val attachment = content.attachments.single()
        attachment.fileName shouldBe "invoice.pdf"
        attachment.contentType shouldBe "application/pdf"
        String(attachment.bytes.copyOfRange(0, 5)) shouldBe "%PDF-"
    }

    @Test
    fun `unicode encoded-word headers are decoded (RFC 2047)`() {
        val content = parsed("unicode-headers.eml")
        content.subject shouldBe "Vérifiez votre adresse émail"
        content.textBody.shouldNotBeNull() shouldContain "accentué"
    }

    @Test
    fun `html-only message extracts anchors and plain-text urls, and never executes anything`() {
        val content = parsed("html-links.eml")
        content.htmlBody.shouldNotBeNull() shouldContain "<script>"
        content.links.map { it.href } shouldContain "https://app.example.com/reset?token=xyz"
        content.links.map { it.href } shouldContain "https://app.example.com/reset-alt?token=xyz"
        // Links are data only — extraction must not have fetched anything (no network here by construction).
    }

    @Test
    fun `broken charset falls back instead of failing the message`() {
        val content = parsed("broken-charset.eml")
        content.textBody.shouldNotBeNull() shouldContain "unknown charset"
    }

    @Test
    fun `malformed inputs yield classified failures, never exceptions`() {
        parser.parse(corpus("malformed-binary.eml")).shouldBeInstanceOf<MimeParseResult.Failed>()
        parser.parse(corpus("malformed-empty.eml")).shouldBeInstanceOf<MimeParseResult.Failed>()
        parser.parse(ByteArray(0)).shouldBeInstanceOf<MimeParseResult.Failed>()
    }

    @Test
    fun `deeply nested multipart bomb is aborted by the depth limit`() {
        val boundary = "b"
        val depth = 60
        val body = StringBuilder()
        body.append("From: a@b.c\r\nSubject: bomb\r\nMIME-Version: 1.0\r\n")
        repeat(depth) { i ->
            body.append("Content-Type: multipart/mixed; boundary=\"$boundary$i\"\r\n\r\n--$boundary$i\r\n")
        }
        body.append("Content-Type: text/plain\r\n\r\ndeep\r\n")
        repeat(depth) { i -> body.append("--$boundary${depth - 1 - i}--\r\n") }
        val result = parser.parse(body.toString().toByteArray())
        result.shouldBeInstanceOf<MimeParseResult.Failed>().reason shouldContain "depth"
    }

    @Test
    fun `part-count bomb is aborted by the part limit`() {
        val parts = StringBuilder()
        parts.append("From: a@b.c\r\nSubject: many\r\nMIME-Version: 1.0\r\n")
        parts.append("Content-Type: multipart/mixed; boundary=\"p\"\r\n\r\n")
        repeat(1000) { parts.append("--p\r\nContent-Type: text/plain\r\n\r\nx\r\n") }
        parts.append("--p--\r\n")
        val result = parser.parse(parts.toString().toByteArray())
        result.shouldBeInstanceOf<MimeParseResult.Failed>()
    }
}
