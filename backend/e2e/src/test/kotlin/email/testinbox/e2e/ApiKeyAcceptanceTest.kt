package email.testinbox.e2e

import email.testinbox.client.ApiScope
import email.testinbox.client.CreateInboxOptions
import email.testinbox.client.MessageMatcher
import email.testinbox.client.TestInboxAuthException
import email.testinbox.client.TestInboxClient
import email.testinbox.client.TestInboxForbiddenException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Black-box acceptance for the TI-002 credential lifecycle, driven through the
 * real JVM SDK against the running stack — including a real SMTP delivery, so
 * the rotated credential is proven on the path a customer actually uses rather
 * than only on the management endpoints.
 */
class ApiKeyAcceptanceTest {
    private fun clientFor(key: String) = TestInboxClient(apiKey = key, baseUrl = E2eStack.keyAdminApiBaseUrl)

    private fun admin() = clientFor(E2eStack.KEY_ADMIN_BOOTSTRAP_KEY)

    @Test
    fun `the whole rotation story, end to end`() {
        // --- the workspace's first managed administrator -------------------
        // Minted with the bootstrap credential, which is the only thing that
        // could authenticate this call on a fresh installation.
        val adminCredential =
            admin().apiKeys.createBlocking(
                scopes = listOf(ApiScope.API_KEYS_MANAGE, ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                name = "e2e-admin",
            )
        val adminClient = clientFor(adminCredential.secret)

        // From here the bootstrap credential is inert (ADR-032 §8) — no
        // restart, no configuration change.
        assertThrows<TestInboxAuthException> { admin().apiKeys.listBlocking() }

        // --- a CI credential, least privilege ------------------------------
        val ci =
            adminClient.apiKeys.createBlocking(
                scopes = listOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                name = "github-ci",
            )
        val ciClient = clientFor(ci.secret)

        // It can do its job…
        val inbox = ciClient.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        E2eStack.sendRawSmtp(
            from = "no-reply@example.com",
            to = inbox.address,
            raw = E2eStack.verificationEmail(inbox.address, subject = "Verify your email"),
        )
        val received =
            inbox.awaitMessageBlocking(
                Duration.ofSeconds(20),
                MessageMatcher.builder().subjectContains("Verify").build(),
            )
        received.subject shouldBe "Verify your email"

        // …and cannot mint or revoke credentials, which is the entire reason
        // key administration is a separate scope.
        assertThrows<TestInboxForbiddenException> {
            ciClient.apiKeys.createBlocking(scopes = listOf(ApiScope.INBOXES_WRITE))
        }
        assertThrows<TestInboxForbiddenException> { ciClient.apiKeys.listBlocking() }

        // --- rotate: both live, then retire the old one --------------------
        val replacement =
            adminClient.apiKeys.createBlocking(
                scopes = listOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ),
                name = "github-ci-rotated",
            )
        val replacementClient = clientFor(replacement.secret)

        // The window in which both work is what makes rotation zero-downtime:
        // the client can be redeployed before anything is revoked.
        ciClient.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        replacementClient.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))

        adminClient.apiKeys.revokeBlocking(ci.apiKey.id)

        // Immediate, with no cache to wait out.
        assertThrows<TestInboxAuthException> {
            ciClient.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        }
        // And the replacement carries on, including on the inbound path.
        val rotatedInbox = replacementClient.createInboxBlocking(CreateInboxOptions(ttl = Duration.ofMinutes(5)))
        E2eStack.sendRawSmtp(
            from = "no-reply@example.com",
            to = rotatedInbox.address,
            raw = E2eStack.verificationEmail(rotatedInbox.address, subject = "After rotation"),
        )
        rotatedInbox
            .awaitMessageBlocking(
                Duration.ofSeconds(20),
                MessageMatcher.builder().subjectContains("After rotation").build(),
            ).subject shouldBe "After rotation"

        // --- what an operator can see afterwards ---------------------------
        val listed = adminClient.apiKeys.listBlocking().items
        val revoked = listed.single { it.id == ci.apiKey.id }
        // Retained, not deleted: the history of a retired credential is what
        // an incident review reads.
        revoked.isRevoked shouldBe true
        revoked.name shouldBe "github-ci"
        // Provenance: who minted what.
        revoked.createdByApiKeyId shouldBe adminCredential.apiKey.id
        // Usage is visible, so an operator can tell a live credential from an
        // abandoned one before revoking it.
        (revoked.lastUsedAt != null) shouldBe true

        // No representation other than the creation response carries a secret.
        listed.forEach { metadata ->
            metadata.toString().contains(ci.secret) shouldBe false
            metadata.toString().contains(replacement.secret) shouldBe false
        }

        // --- break-glass ---------------------------------------------------
        // Revoking the last administrator is permitted — it is exactly what a
        // compromised administrator requires — and the bootstrap credential
        // reopens so the workspace is recoverable.
        adminClient.apiKeys.revokeBlocking(adminCredential.apiKey.id)
        admin()
            .apiKeys
            .listBlocking()
            .items
            .isNotEmpty() shouldBe true
    }
}
