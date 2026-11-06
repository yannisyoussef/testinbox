package email.testinbox.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** M0: N may only lower the cohort's thirty places; the hard bound is unchanged. */
class BetaParticipantCapTest {
    @Test
    fun `the default is the hard bound of thirty`() {
        BetaParticipantCap.HARD_LIMIT.value shouldBe 30
        BetaParticipantCap.HARD_LIMIT.value shouldBe BetaCohortPolicy.MAX_PARTICIPANTS
        BetaParticipantCap.HARD_LIMIT.unlowered shouldBe true
        BetaParticipantCap(10).unlowered shouldBe false
    }

    @Test
    fun `every value in 1-30 is a cap, and nothing outside it`() {
        for (n in 1..30) BetaParticipantCap(n).value shouldBe n
        for (n in listOf(Int.MIN_VALUE, -1, 0, 31, 32, Int.MAX_VALUE)) {
            shouldThrow<IllegalArgumentException> { BetaParticipantCap(n) }.message shouldContain
                "testinbox.beta.max-participants must be in 1..30"
        }
    }

    @Test
    fun `a cap admits exactly the places up to N`() {
        val ten = BetaParticipantCap(10)
        (1..10).all(ten::admits) shouldBe true
        ten.admits(11) shouldBe false
        BetaParticipantCap.HARD_LIMIT.admits(30) shouldBe true
        BetaParticipantCap.HARD_LIMIT.admits(31) shouldBe false
    }
}
