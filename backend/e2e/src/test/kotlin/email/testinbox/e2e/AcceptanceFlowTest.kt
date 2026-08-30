package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxNotFoundException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * The full black-box MVP acceptance flow (quality strategy §15), driven
 * end-to-end through the real JVM SDK, real HTTP, and a real SMTP session:
 * create inbox → send MIME → deterministic wait → assertions → link
 * extraction → raw MIME → delete → verified data cleanup.
 */
class AcceptanceFlowTest {
    private val testInbox = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    @Test
    fun `create - receive - wait - assert - extract link - raw - delete - cleanup`() {
        // 1. Create an authenticated inbox with a generated address.
        val inbox =
            testInbox.createInboxBlocking(
                CreateInboxOptions(ttl = Duration.ofMinutes(5), aliasHint = "signup"),
            )
        inbox.address shouldEndWith "@${E2eStack.MAIL_DOMAIN}"
        inbox.state shouldBe "ACTIVE"

        // 2. The system under test sends a real MIME email over SMTP.
        E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, E2eStack.verificationEmail(inbox.address))

        // 3. Deterministic wait for the matching message.
        val message =
            inbox.awaitMessageBlocking(
                Duration.ofSeconds(20),
                email.testinbox.client.MessageMatcher
                    .builder()
                    .from("no-reply@example.com")
                    .subjectContains("Verify")
                    .build(),
            )

        // 4-6. Assert sender, subject, body.
        message.from shouldBe "no-reply@example.com"
        message.subject shouldBe "Verify your email"
        message.textBody.shouldNotBeNull() shouldContain "Welcome!"

        // 7. Extract the verification link.
        val verificationUrl = message.links.first { it.href.contains("/verify") }.href
        verificationUrl shouldBe "https://app.example.com/verify?token=abc123"

        // 8. Retrieve the raw MIME.
        val raw = String(message.rawBlocking(), Charsets.ISO_8859_1)
        raw shouldContain "Subject: Verify your email"
        raw shouldContain "token=abc123"

        // 9. Delete the inbox.
        inbox.deleteBlocking()

        // 10. Verify data cleanup: inbox, message, and raw MIME all become
        // unreachable once the bounded sweep completes (ADR-009).
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThrows<TestInboxNotFoundException> { testInbox.getInboxBlocking(inbox.id) }
            assertThrows<TestInboxNotFoundException> { testInbox.getMessageBlocking(message.id) }
        }
        val keys =
            E2eStack.apiContext
                .getBean(email.testinbox.application.port.BlobStore::class.java)
                .listKeysOlderThan("", Instant.now().plusSeconds(60))
                .filter { it.contains(inbox.id) }
        keys.shouldBeEmpty()
    }

    @Test
    fun `unmatched wait times out with diagnostics through the SDK`() {
        val inbox = testInbox.createInboxBlocking()
        // Diagnostics count messages that became visible DURING the wait window
        // (wait-semantics.md), so deliver the unrelated message mid-wait.
        val sender =
            Thread {
                Thread.sleep(500)
                E2eStack.sendRawSmtp(
                    "other@example.com",
                    inbox.address,
                    E2eStack.verificationEmail(inbox.address, subject = "Unrelated"),
                )
            }.apply { start() }
        val exception =
            assertThrows<email.testinbox.client.TestInboxTimeoutException> {
                inbox.awaitMessageBlocking(
                    Duration.ofSeconds(4),
                    email.testinbox.client.MessageMatcher
                        .builder()
                        .subjectContains("never-sent")
                        .build(),
                )
            }
        sender.join(5000)
        exception.arrivedButUnmatchedCount shouldBe 1
        inbox.deleteBlocking()
    }
}
