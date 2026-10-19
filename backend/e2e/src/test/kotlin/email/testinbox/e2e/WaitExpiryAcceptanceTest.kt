package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxInboxGoneException
import email.testinbox.client.TestInboxNotFoundException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

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
    ): HttpResponse<String> {
        val body = """{"timeoutSeconds":$timeoutSeconds,"matcher":{"subjectContains":"never-sent"}}"""
        return http.send(
            HttpRequest
                .newBuilder(URI.create("${E2eStack.apiBaseUrl}/v1/inboxes/$inboxId/messages/wait"))
                .header("Authorization", "Bearer ${E2eStack.API_KEY}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

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
    fun `a parked SDK wait ends Gone when its inbox's TTL runs out, and the sweep then removes every trace`() {
        // The stack runs with sweep-interval=1s and expiry-grace=1s: ACTIVE -> EXPIRING at
        // the TTL, EXPIRED one grace later, hard delete on a following sweep (ADR-009).
        // Nothing here calls DELETE — this is the scheduler's own transition, observed
        // through the real LISTEN/NOTIFY path.
        val client = client()
        val inbox = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofSeconds(2)))
        // A message that does NOT match arrives first: the waiter must still end Gone,
        // and must not be told anything about the inbox after it stopped serving.
        E2eStack.sendRawSmtp("no-reply@example.com", inbox.address, E2eStack.verificationEmail(inbox.address, "Unrelated"))
        val started = System.nanoTime()
        val error =
            assertThrows<TestInboxInboxGoneException> {
                inbox.awaitMessageBlocking(Duration.ofSeconds(20), MessageMatcher.builder().subjectContains("never").build())
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        error.status shouldBe 410
        error.problemType shouldBe "https://testinbox.email/problems/inbox-gone"
        // TTL + grace + a sweep tick is a few seconds; the 20 s window never decided this.
        elapsedMs shouldBeLessThan 10_000
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThrows<TestInboxNotFoundException> { client.getInboxBlocking(inbox.id) }
        }
        E2eStack.apiContext
            .getBean(email.testinbox.application.port.BlobStore::class.java)
            .listKeysOlderThan("", Instant.now().plusSeconds(60))
            .filter { it.contains(inbox.id) }
            .shouldBeEmpty()
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
