package email.testinbox.domain.inbox

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LocalPartPolicyTest {
    @Test
    fun `accepts and normalizes a valid local part`() {
        val result = LocalPartPolicy.validate("  QA.Signup-Test_01 ")
        result.shouldBeInstanceOf<LocalPartPolicy.Result.Valid>().normalized shouldBe "qa.signup-test_01"
    }

    @Test
    fun `rejects empty, overlong and malformed local parts`() {
        LocalPartPolicy.validate("").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("a".repeat(65)).shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate(".leading").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("trailing-").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("two..dots").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("\"quoted\"").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("spa ce").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
        LocalPartPolicy.validate("naïve").shouldBeInstanceOf<LocalPartPolicy.Result.Invalid>()
    }

    @Test
    fun `denies RFC 2142 role addresses and sensitive names regardless of case`() {
        for (name in listOf("postmaster", "Abuse", "ADMIN", "noreply", "security")) {
            LocalPartPolicy.validate(name).shouldBeInstanceOf<LocalPartPolicy.Result.Denied>()
        }
    }

    @Test
    fun `property - every accepted local part is lowercase, bounded and re-validates to itself`() {
        runBlocking {
            checkAll(Arb.stringPattern("[a-zA-Z0-9][a-zA-Z0-9._-]{0,40}[a-zA-Z0-9]")) { candidate ->
                when (val result = LocalPartPolicy.validate(candidate)) {
                    is LocalPartPolicy.Result.Valid -> {
                        val normalized = result.normalized
                        normalized shouldBe normalized.lowercase()
                        (normalized.length <= 64) shouldBe true
                        // Idempotent: validating the normalized form yields the same value.
                        LocalPartPolicy.validate(normalized)
                            .shouldBeInstanceOf<LocalPartPolicy.Result.Valid>()
                            .normalized shouldBe normalized
                    }
                    else -> {} // rejected inputs (e.g. "..", denylist) are fine here
                }
            }
        }
    }
}
