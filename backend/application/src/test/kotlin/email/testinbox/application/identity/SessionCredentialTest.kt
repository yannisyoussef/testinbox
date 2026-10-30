package email.testinbox.application.identity

import email.testinbox.application.Sha256
import email.testinbox.application.usecase.IssueHumanSession
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionCredentialTest {
    @Test
    fun `credentials carry 256 random bits in a bounded cookie safe representation`() {
        val credentials = (1..100).map { SessionCredential.generate() }
        credentials.map { it.value }.toSet().size shouldBe 100
        credentials.forEach {
            it.value.matches(Regex("[A-Za-z0-9_-]{43}")) shouldBe true
            SessionCredential.parse(it.value)?.verifier() shouldBe Sha256.hex(it.value)
        }
    }

    @Test
    fun `token and issuance string representations redact credentials`() {
        val credential = SessionCredential.generate()
        credential.toString().contains(credential.value) shouldBe false
        IssueHumanSession.Issued(credential, Instant.EPOCH).toString().contains(credential.value) shouldBe false
    }

    @Test
    fun `parsing does not trim or accept surrounding whitespace or alternate token types`() {
        val value = SessionCredential.generate().value
        listOf(null, "", " $value", "$value\n", "Bearer $value", "ti_k1_$value")
            .forEach { SessionCredential.parse(it) shouldBe null }
    }
}
