package email.testinbox.domain.inbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class GeneratedAddressTest {
    @Test
    fun `tokens are 26 chars of base32 and never repeat across draws`() {
        val tokens = (1..1000).map { GeneratedAddress.newToken() }
        tokens.forEach { it shouldMatch Regex("[a-z2-7]{26}") }
        tokens.toSet().size shouldBe tokens.size
    }

    @Test
    fun `alias hint is normalized into a safe bounded prefix`() {
        GeneratedAddress.normalizeHint("Signup Flow! Ünicode") shouldBe "signup-flow---nicode"
        GeneratedAddress.normalizeHint("--..--") shouldBe null
        GeneratedAddress.normalizeHint(null) shouldBe null
        GeneratedAddress.normalizeHint("x".repeat(50))!!.length shouldBe 20
    }

    @Test
    fun `local part carries the hint as prefix and always ends with a fresh token`() {
        val localPart = GeneratedAddress.localPart("signup")
        localPart shouldMatch Regex("signup-[a-z2-7]{26}")
        GeneratedAddress.localPart(null) shouldMatch Regex("[a-z2-7]{26}")
        // Valid under the strictest interpretation of our own policy.
        val validated = LocalPartPolicy.validate(localPart)
        (validated is LocalPartPolicy.Result.Valid) shouldBe true
    }
}
