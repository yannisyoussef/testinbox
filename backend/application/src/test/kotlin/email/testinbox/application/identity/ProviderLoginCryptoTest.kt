package email.testinbox.application.identity

import email.testinbox.domain.identity.LoginProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Base64

class ProviderLoginCryptoTest {
    private val crypto = ProviderLoginCrypto(ByteArray(32) { 11 })
    private val hash = crypto.stateHash(SessionCredential.generate().value)

    @Test
    fun `protocol state encrypts with a fresh nonce and round trips without exposing PKCE`() {
        val plain = """{"code_verifier":"fixture-pkce","nonce":"fixture-nonce"}"""
        val first = crypto.seal(LoginProvider.GOOGLE, hash, plain)
        val second = crypto.seal(LoginProvider.GOOGLE, hash, plain)
        (first == second) shouldBe false
        first.contains("fixture-pkce") shouldBe false
        crypto.open(LoginProvider.GOOGLE, hash, first) shouldBe plain
        crypto.open(LoginProvider.GOOGLE, hash, second) shouldBe plain
    }

    @Test
    fun `provider state context key and ciphertext tampering all fail authentication`() {
        val sealed = crypto.seal(LoginProvider.GOOGLE, hash, "protocol-fixture")
        crypto.open(LoginProvider.GITHUB, hash, sealed) shouldBe null
        crypto.open(LoginProvider.GOOGLE, "a".repeat(64), sealed) shouldBe null
        ProviderLoginCrypto(ByteArray(32) { 12 }).open(LoginProvider.GOOGLE, hash, sealed) shouldBe null
        val altered = Base64.getDecoder().decode(sealed)
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        crypto.open(LoginProvider.GOOGLE, hash, Base64.getEncoder().encodeToString(altered)) shouldBe null
    }

    @Test
    fun `state and payload sizes are bounded before storage or decryption`() {
        listOf("", "short", "a".repeat(257), "contains spaces", "a/".repeat(20)).forEach { crypto.validState(it) shouldBe false }
        shouldThrow<IllegalArgumentException> { crypto.seal(LoginProvider.GOOGLE, hash, "x".repeat(8193)) }
        crypto.open(LoginProvider.GOOGLE, hash, "!") shouldBe null
        crypto.open(LoginProvider.GOOGLE, hash, "a".repeat(16385)) shouldBe null
        shouldThrow<IllegalArgumentException> { ProviderLoginCrypto(ByteArray(31)) }
        crypto.toString() shouldBe "ProviderLoginCrypto([REDACTED])"
    }

    @Test
    fun `browser source and state hashes use separate purposes and source hashing is keyed`() {
        val token = SessionCredential.generate()
        (crypto.browserHash(token) == token.verifier()) shouldBe false
        (crypto.browserHash(token) == crypto.stateHash(token.value)) shouldBe false
        (crypto.sourceHash("198.51.100.1") == ProviderLoginCrypto(ByteArray(32) { 12 }).sourceHash("198.51.100.1")) shouldBe false
    }
}
