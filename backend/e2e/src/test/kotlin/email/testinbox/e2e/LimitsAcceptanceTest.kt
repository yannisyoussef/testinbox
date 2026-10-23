package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxQuotaExceededException
import email.testinbox.client.TestInboxRateLimitException
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Black-box acceptance for ADR-027, driven through the real JVM SDK against
 * the running stack — the scenarios TI-001 specifies.
 *
 * The limits under test are configured on the dedicated e2e workspaces in
 * [E2eStack], so these scenarios never depend on how much the other
 * acceptance tests happen to have consumed.
 */
class LimitsAcceptanceTest {
    private fun quotaClient() = TestInboxClient(apiKey = E2eStack.QUOTA_API_KEY, baseUrl = E2eStack.quotaApiBaseUrl)

    private fun rateClient() = TestInboxClient(apiKey = E2eStack.RATE_API_KEY, baseUrl = E2eStack.rateApiBaseUrl)

    @Test
    fun `quota permits two active inboxes, refuses the third, and recovers after a delete`() {
        val client = quotaClient()

        val a = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        val b = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))

        // The third exceeds the workspace allowance.
        val rejected =
            assertThrows<TestInboxQuotaExceededException> {
                client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
            }
        rejected.quota shouldBe "ACTIVE_INBOXES"
        rejected.limit shouldBe 2L

        // Freeing capacity makes the refusal's advertised remedy actually work.
        a.deleteBlocking()
        val c = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        c.address.isNotBlank() shouldBe true

        b.deleteBlocking()
        c.deleteBlocking()
    }

    @Test
    fun `the create rate limit admits the configured burst then answers 429 with a retry hint`() {
        val client = rateClient()

        // Capacity is 2 for this workspace.
        val first = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        val second = client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))

        val limited =
            assertThrows<TestInboxRateLimitException> {
                client.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
            }
        limited.category shouldBe "INBOX_CREATE"
        // Waiting helps here, so the server must say for how long — in whole
        // seconds and never zero, or a client would hot-loop.
        limited.retryAfter!!.toSeconds() shouldBeGreaterThanOrEqual 1L

        first.deleteBlocking()
        second.deleteBlocking()
    }

    @Test
    fun `one workspace exhausting its limits never degrades another`() {
        // Exhaust both restricted tenants here rather than relying on the other
        // tests having run first — JUnit method order is not guaranteed, and a
        // test whose premise is a comment can pass without ever establishing it.
        val rateLimited = rateClient()
        val created =
            generateSequence { runCatching { rateLimited.createInboxBlocking() }.getOrNull() }
                .take(3)
                .toList()
        assertThrows<TestInboxRateLimitException> { rateLimited.createInboxBlocking() }

        val quotaBound = quotaClient()
        val quotaInboxes =
            generateSequence { runCatching { quotaBound.createInboxBlocking() }.getOrNull() }
                .take(2)
                .toList()
        assertThrows<TestInboxQuotaExceededException> { quotaBound.createInboxBlocking() }

        // With both neighbours wedged, an unrelated tenant is served normally.
        val neighbour = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)
        val inbox = neighbour.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        inbox.address.isNotBlank() shouldBe true

        // And the full delivery loop still works for that tenant.
        E2eStack.sendRawSmtp(
            "no-reply@example.com",
            inbox.address,
            E2eStack.verificationEmail(inbox.address, subject = "Unaffected by a neighbour"),
        )
        val message = inbox.awaitMessageBlocking(Duration.ofSeconds(20))
        message.subject shouldBe "Unaffected by a neighbour"

        inbox.deleteBlocking()
        created.forEach { runCatching { it.deleteBlocking() } }
        quotaInboxes.forEach { runCatching { it.deleteBlocking() } }
    }
}
