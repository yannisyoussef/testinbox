package email.testinbox.e2e

import email.testinbox.client.MessageMatcher
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxInboxGoneException
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Issue #87 on the full stack: a wait parked on an ACTIVE inbox ends `410`
 * as soon as the inbox stops serving, through the real LISTEN/NOTIFY path,
 * not at the end of its window — and a message that arrived first is not
 * handed to it afterwards. Both SDK and raw REST see the same contract.
 */
class WaitExpiryAcceptanceTest {
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun client() = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    private fun rawWait(
        inboxId: String,
        timeoutSeconds: Int,
    ): HttpResponse<String> =
        http.send(
            HttpRequest
                .newBuilder(URI.create("${E2eStack.apiBaseUrl}/v1/inboxes/$inboxId/messages/wait"))
                .header("Authorization", "Bearer ${E2eStack.API_KEY}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"timeoutSeconds":$timeoutSeconds,"matcher":{"subjectContains":"never-sent"}}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `a parked SDK wait ends with the typed Gone error the moment its inbox is deleted`() {
        val client = client()
        val inbox = client.createInboxBlocking()
        val deleter =
            Thread {
                Thread.sleep(700)
                inbox.deleteBlocking()
            }.apply { start() }
        val started = System.nanoTime()
        val error =
            assertThrows<TestInboxInboxGoneException> {
                inbox.awaitMessageBlocking(Duration.ofSeconds(20), MessageMatcher.builder().subjectContains("never").build())
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        deleter.join(5000)
        error.status shouldBe 410
        error.problemType shouldBe "https://testinbox.email/problems/inbox-gone"
        // Woken by the transition's notification, not by the 20 s window.
        elapsedMs shouldBeLessThan 10_000
    }

    @Test
    fun `a parked raw REST wait answers 410 after the inbox is deleted, even though a message arrived first`() {
        val client = client()
        val inbox = client.createInboxBlocking()
        val actor =
            Thread {
                Thread.sleep(700)
                // A message that does NOT match, then the deletion: the waiter must not be told
                // anything about the inbox after it stopped serving.
                E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, E2eStack.verificationEmail(inbox.address, "Unrelated"))
                Thread.sleep(300)
                inbox.deleteBlocking()
            }.apply { start() }
        val response = rawWait(inbox.id, timeoutSeconds = 20)
        actor.join(10_000)
        response.statusCode() shouldBe 410
        response.body().contains("inbox-gone") shouldBe true
    }
}
