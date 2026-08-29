package email.testinbox.domain.message

import email.testinbox.domain.InboxId
import email.testinbox.domain.MessageId
import email.testinbox.domain.WorkspaceId
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test

class MessageMatcherTest {
    private fun message(
        parseStatus: ParseStatus = ParseStatus.OK,
        fromAddress: String? = "no-reply@example.com",
        fromHeader: String? = "SUT <no-reply@example.com>",
        subject: String? = "Verify your email",
        headers: List<EmailHeader> = listOf(EmailHeader("X-Test-Run", "42")),
    ): Message =
        Message(
            id = MessageId(UUID.randomUUID()),
            workspaceId = WorkspaceId(UUID.randomUUID()),
            inboxId = InboxId(UUID.randomUUID()),
            receivedAt = Instant.parse("2026-08-29T12:00:00Z"),
            provider = "local-smtp",
            providerMessageId = null,
            envelopeFrom = "no-reply@example.com",
            envelopeTo = "inbox@testinbox.local",
            rawObjectKey = "k",
            rawSizeBytes = 1,
            contentFingerprint = "f",
            possibleDuplicateOfMessageId = null,
            parseStatus = parseStatus,
            parseError = null,
            parsed =
                if (parseStatus == ParseStatus.OK) {
                    ParsedContent(fromAddress, fromHeader, null, subject, "hi", null, headers, emptyList())
                } else {
                    null
                },
            attachments = emptyList(),
        )

    @Test
    fun `empty matcher matches any parsed message but never a parse-failed one`() {
        MessageMatcher().matches(message()) shouldBe true
        MessageMatcher().matches(message(parseStatus = ParseStatus.FAILED)) shouldBe false
    }

    @Test
    fun `from matches the parsed address case-insensitively`() {
        MessageMatcher(from = "NO-REPLY@example.com").matches(message()) shouldBe true
        MessageMatcher(from = "other@example.com").matches(message()) shouldBe false
    }

    @Test
    fun `subject matchers`() {
        MessageMatcher(subjectContains = "Verify").matches(message()) shouldBe true
        MessageMatcher(subjectContains = "verify").matches(message()) shouldBe false
        MessageMatcher(subjectEquals = "Verify your email").matches(message()) shouldBe true
        MessageMatcher(subjectEquals = "Verify").matches(message()) shouldBe false
        MessageMatcher(subjectContains = "x").matches(message(subject = null)) shouldBe false
    }

    @Test
    fun `header matchers support presence and exact value`() {
        MessageMatcher(headers = listOf(HeaderMatcher("x-test-run"))).matches(message()) shouldBe true
        MessageMatcher(headers = listOf(HeaderMatcher("X-Test-Run", "42"))).matches(message()) shouldBe true
        MessageMatcher(headers = listOf(HeaderMatcher("X-Test-Run", "43"))).matches(message()) shouldBe false
        MessageMatcher(headers = listOf(HeaderMatcher("X-Missing"))).matches(message()) shouldBe false
    }

    @Test
    fun `all specified fields must match together`() {
        val matcher = MessageMatcher(from = "no-reply@example.com", subjectContains = "Verify")
        matcher.matches(message()) shouldBe true
        matcher.matches(message(subject = "Welcome")) shouldBe false
        matcher.matches(message(fromAddress = "x@y.z", fromHeader = null)) shouldBe false
    }
}
