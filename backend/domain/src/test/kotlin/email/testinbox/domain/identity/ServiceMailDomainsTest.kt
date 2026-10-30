package email.testinbox.domain.identity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** TestInbox's own inbound domains are never a login or contact identity (PR #114 security review P1-1). */
class ServiceMailDomainsTest {
    private val domains = ServiceMailDomains(listOf("Inbox.TestInbox.Email."))

    private fun owns(address: String) = domains.owns(checkNotNull(ContactEmail.parse(address)))

    @Test
    fun `the inbound domain and every subdomain are owned, case-insensitively`() {
        owns("alice@inbox.testinbox.email") shouldBe true
        owns("ALICE@INBOX.TESTINBOX.EMAIL") shouldBe true
        owns("alice@deep.inbox.testinbox.email") shouldBe true
    }

    @Test
    fun `look-alike and parent domains are ordinary addresses`() {
        owns("alice@evil-inbox.testinbox.email") shouldBe false
        owns("alice@inbox.testinbox.email.evil.example") shouldBe false
        owns("alice@testinbox.email") shouldBe false
        owns("alice@example.com") shouldBe false
    }

    @Test
    fun `at least one domain is required - an empty set would silently allow every inbox`() {
        assertThrows<IllegalArgumentException> { ServiceMailDomains(listOf(" ", "")) }
    }
}
