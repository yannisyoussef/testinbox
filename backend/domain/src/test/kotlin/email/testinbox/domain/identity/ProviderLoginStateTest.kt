package email.testinbox.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant

class ProviderLoginStateTest {
    private val now = Instant.parse("2026-10-29T12:00:00Z")

    private fun state() =
        ProviderLoginState(
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            LoginProvider.GOOGLE,
            "encrypted-fixture",
            now,
            now.plusSeconds(600),
        )

    @Test
    fun `state requires the correct provider hash shape and bounded lifetime`() {
        shouldThrow<IllegalArgumentException> { state().copy(provider = LoginProvider.EMAIL) }
        shouldThrow<IllegalArgumentException> { state().copy(browserHash = "plaintext") }
        shouldThrow<IllegalArgumentException> { state().copy(expiresAt = now) }
        shouldThrow<IllegalArgumentException> { state().copy(expiresAt = now.plusSeconds(601)) }
        shouldThrow<IllegalArgumentException> { state().copy(encryptedRequest = "x".repeat(16385)) }
    }

    @Test
    fun `protocol payload is never included in its default string representation`() {
        state().toString().contains("encrypted-fixture") shouldBe false
    }
}
