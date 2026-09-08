package email.testinbox.domain.idempotency

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The bounds and the character class are load-bearing, not cosmetic
 * (ADR-033 §10).
 *
 * Too short and a tenant's own CI jobs collide by accident; too long and the
 * value becomes a storage and logging burden. The character class exists so a
 * customer-generated value that will be stored and audited cannot carry a
 * control character — a key containing `\r\n` in a header or an audit line is
 * a response-splitting primitive, and the validator is the only thing
 * standing in front of it.
 */
class IdempotencyKeyTest {
    private fun ascii(length: Int) = "k".repeat(length)

    @Test
    fun `the length bounds are inclusive at both ends`() {
        IdempotencyKey.of(ascii(IdempotencyKey.MIN_LENGTH - 1)) shouldBe null
        IdempotencyKey.of(ascii(IdempotencyKey.MIN_LENGTH)) shouldNotBe null
        IdempotencyKey.of(ascii(IdempotencyKey.MAX_LENGTH)) shouldNotBe null
        IdempotencyKey.of(ascii(IdempotencyKey.MAX_LENGTH + 1)) shouldBe null
        IdempotencyKey.of("") shouldBe null
    }

    @Test
    fun `a space is rejected, so a slugless test name cannot be a key`() {
        // Documented in docs/dev/idempotency.md: `sends welcome email` is
        // rejected and `sends-welcome-email` is not. 0x20 sits just below the
        // allowed range, which is easy to get wrong by one in either direction.
        IdempotencyKey.of("sends welcome email") shouldBe null
        IdempotencyKey.of("sends-welcome-email") shouldNotBe null
    }

    @Test
    fun `control characters cannot enter a header or an audit line`() {
        // The reason the character class exists at all. Each of these is a
        // valid-length key that would otherwise be stored and echoed.
        listOf(
            "line\nbreak-padding-x",
            "carriage\rreturn-pad-x",
            "tab\tseparated-padxxx",
            "nul\u0000byte-padding-x",
            "del\u007Fchar-padding-x",
            "non-ascii-\u00e9-padding-x",
        ).forEach { candidate ->
            // Guard the guard: a candidate that failed on length instead of
            // charset would prove nothing about the charset.
            (candidate.length >= IdempotencyKey.MIN_LENGTH) shouldBe true
            IdempotencyKey.of(candidate) shouldBe null
        }
    }

    @Test
    fun `the printable range is accepted end to end`() {
        // 0x21..0x7e inclusive — punctuation included, since a caller may use
        // a URL, a path or a JSON-ish job id as a key.
        val everyAllowedCharacter = (0x21..0x7e).map { it.toChar() }.joinToString("")
        IdempotencyKey.of(everyAllowedCharacter)?.value shouldBe everyAllowedCharacter
        IdempotencyKey.of("ci/job:1234#attempt-1") shouldNotBe null
    }

    @Test
    fun `toString never renders the key, so it cannot reach a log by accident`() {
        // The anti-logging guarantee is one override on a value class, reached
        // transitively through IdempotencyRequest. A refactor that drops it
        // would put a customer identifier into our logs and their CI logs at
        // once, silently — this is what notices.
        val secretish = "customer-identifier-in-here"
        val key = IdempotencyKey.of(secretish)!!
        key.toString() shouldNotContain secretish
        key.toString() shouldBe "IdempotencyKey(<redacted>)"
        // The value is still reachable deliberately, for hashing.
        key.value shouldBe secretish
    }
}
