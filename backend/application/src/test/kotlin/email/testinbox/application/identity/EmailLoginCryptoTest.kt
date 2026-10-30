package email.testinbox.application.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class EmailLoginCryptoTest {
    private val key = ByteArray(32) { 7 }
    private val crypto = EmailLoginCrypto(key)

    @Test
    fun `codes have eight digits and string representations never expose them`() {
        repeat(100) {
            val code = EmailLoginCode.generate()
            code.value.matches(Regex("[0-9]{8}")) shouldBe true
            code.toString() shouldBe "EmailLoginCode([REDACTED])"
        }
    }

    @Test
    fun `code proof is keyed and bound to its challenge`() {
        val id = UUID.randomUUID()
        val verifier = crypto.codeVerifier(id, "12345678")
        verifier.matches(Regex("[0-9a-f]{64}")) shouldBe true
        crypto.matches(verifier, crypto.codeVerifier(id, "12345678")) shouldBe true
        crypto.matches(verifier, crypto.codeVerifier(UUID.randomUUID(), "12345678")) shouldBe false
        crypto.matches(verifier, EmailLoginCrypto(ByteArray(32) { 8 }).codeVerifier(id, "12345678")) shouldBe false
        crypto.matches(verifier, crypto.codeVerifier(id, "87654321")) shouldBe false
    }

    @Test
    fun `browser proof is purpose separated from session verifier and source hashing is keyed`() {
        val browser = SessionCredential.generate()
        (crypto.browserHash(browser) == browser.verifier()) shouldBe false
        (crypto.sourceHash("198.51.100.1") == EmailLoginCrypto(ByteArray(32) { 8 }).sourceHash("198.51.100.1")) shouldBe false
    }

    @Test
    fun `key material is copied validated and redacted`() {
        val id = UUID.randomUUID()
        val before = crypto.codeVerifier(id, "12345678")
        key.fill(0)
        crypto.codeVerifier(id, "12345678") shouldBe before
        crypto.toString() shouldBe "EmailLoginCrypto([REDACTED])"
        shouldThrow<IllegalArgumentException> { EmailLoginCrypto(ByteArray(31)) }
        shouldThrow<IllegalArgumentException> { EmailLoginCrypto.fromBase64("not base64") }
        EmailLoginCrypto.fromBase64(Base64.getEncoder().encodeToString(ByteArray(32) { 7 })).codeVerifier(id, "12345678") shouldBe before
    }
}
