package email.testinbox.application.deployment

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

class DatabaseSessionPolicyTest {
    private fun policyFor(raw: String) = DatabaseSessionPolicy { raw }

    @Test
    fun `parses every unit PostgreSQL renders a duration GUC in`() {
        SessionTimeout.parse("30s") shouldBe Duration.ofSeconds(30)
        SessionTimeout.parse("5min") shouldBe Duration.ofMinutes(5)
        SessionTimeout.parse("1h") shouldBe Duration.ofHours(1)
        SessionTimeout.parse("2d") shouldBe Duration.ofDays(2)
        SessionTimeout.parse("250ms") shouldBe Duration.ofMillis(250)
        SessionTimeout.parse("500us") shouldBe Duration.ofNanos(500_000)
        // A bare number is the setting's base unit, which is milliseconds here.
        SessionTimeout.parse("30000") shouldBe Duration.ofMillis(30_000)
        SessionTimeout.parse(" 30s ") shouldBe Duration.ofSeconds(30)
    }

    @Test
    fun `rejects what is not a duration rather than guessing`() {
        SessionTimeout.parse("thirty seconds").shouldBeNull()
        SessionTimeout.parse("30 minutes").shouldBeNull()
        SessionTimeout.parse("-1s").shouldBeNull()
        SessionTimeout.parse("").shouldBeNull()
    }

    @Test
    fun `the PostgreSQL default - disabled - is reported as unbounded`() {
        val status = policyFor("0").status()
        status.bounded shouldBe false
        status.timeout shouldBe Duration.ZERO
        status.detail shouldContain "disabled"
        status.detail shouldContain "ADR-033"
    }

    @Test
    fun `the recommended value is bounded and reported verbatim`() {
        val status = policyFor("30s").status()
        status.bounded shouldBe true
        status.raw shouldBe "30s"
        status.detail shouldBe "idle_in_transaction_session_timeout is 30s"
    }

    @Test
    fun `a timeout below the claim-wait ceiling is bounded but flagged`() {
        // It still bounds a hung claim — that is the ADR-033 requirement — but
        // it can reap a healthy claim that is legitimately waiting.
        val status = policyFor("5s").status()
        status.bounded shouldBe true
        status.detail shouldContain "below the 30s"
    }

    @Test
    fun `an unparseable value fails closed`() {
        val status = policyFor("banana").status()
        status.bounded shouldBe false
        status.timeout.shouldBeNull()
        status.detail shouldContain "not a duration"
    }
}
