package email.testinbox.e2e

import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxIdempotencyConflictException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.UUID

/**
 * Black-box acceptance for ADR-033, driven through the real JVM SDK against
 * the running stack — the scenario TI-003 §29 specifies.
 *
 * The failure this exists for cannot be staged directly: a response lost in
 * transit is, from the client's side, indistinguishable from sending the same
 * request twice. So that is what this does.
 */
class IdempotencyAcceptanceTest {
    private fun client() = TestInboxClient(apiKey = E2eStack.API_KEY, baseUrl = E2eStack.apiBaseUrl)

    @Test
    fun `a retried create returns the same inbox, and a changed one under the same key is refused`() {
        val key = "e2e-${UUID.randomUUID()}"
        val options = CreateInboxOptions(ttl = Duration.ofMinutes(10), idempotencyKey = key)

        val first = client().createInboxBlocking(options)
        val retry = client().createInboxBlocking(options)

        // One resource, however many times the client asked for it.
        retry.id shouldBe first.id
        retry.address shouldBe first.address

        // The same key with different input is terminal: executing it would
        // silently give the client something other than what it asked for.
        assertThrows<TestInboxIdempotencyConflictException> {
            client().createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(20), idempotencyKey = key))
        }

        // And the original is untouched by the refusal.
        client().getInboxBlocking(first.id).address shouldBe first.address
        first.deleteBlocking()
    }

    @Test
    fun `the replayed inbox still receives mail, so a replay is not a hollow copy`() {
        val key = "e2e-${UUID.randomUUID()}"
        val options = CreateInboxOptions(ttl = Duration.ofMinutes(10), idempotencyKey = key)
        val inbox = client().createInboxBlocking(options)
        val replayed = client().createInboxBlocking(options)
        replayed.id shouldBe inbox.id

        E2eStack.sendRawSmtp(
            from = "no-reply@example.com",
            to = replayed.address,
            raw = E2eStack.verificationEmail(replayed.address, subject = "Replayed inbox works"),
        )
        replayed
            .awaitMessageBlocking(Duration.ofSeconds(20))
            .subject shouldBe "Replayed inbox works"
        inbox.deleteBlocking()
    }

    @Test
    fun `without a key the same request twice creates two inboxes`() {
        // The header is opt-in, and its absence must change nothing.
        val a = client().createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        val b = client().createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        a.id shouldNotBe b.id
        a.deleteBlocking()
        b.deleteBlocking()
    }

    @Test
    fun `a key is scoped to its operation, so one job id serves every call it makes`() {
        val key = "e2e-shared-${UUID.randomUUID()}"
        val inbox = client().createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5), idempotencyKey = key))
        // A CI job that uses its build id everywhere must not have its second
        // endpoint call refused as a reuse of the first.
        val second = client().createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5), idempotencyKey = key))
        second.id shouldBe inbox.id
        inbox.deleteBlocking()
    }
}
