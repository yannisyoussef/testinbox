package email.testinbox.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class AccountDeletionPolicyTest {
    private val requested = Instant.parse("2026-10-30T00:00:00Z")

    @Test
    fun `the stage follows what remains - draining first, then content, then nothing`() {
        AccountContentRemaining(1, 3, 2).stage shouldBe AccountDeletionStage.DRAINING
        AccountContentRemaining(0, 3, 0).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        // A live EXACT cooldown alone keeps the deletion open: erasing would release the address early.
        AccountContentRemaining(0, 0, 1).stage shouldBe AccountDeletionStage.CONTENT_DELETING
        AccountContentRemaining.NONE.none shouldBe true
        AccountContentRemaining.NONE.stage shouldBe AccountDeletionStage.ERASED
    }

    @Test
    fun `the derived threshold is the EXACT cooldown plus retention T_max plus a day of slack`() {
        val policy = AccountDeletionPolicy.derived(Duration.ofHours(24), Duration.ofHours(24))
        policy.overdueAfter shouldBe Duration.ofHours(72)
        policy.overdue(AccountDeletionStage.CONTENT_DELETING, requested, requested.plus(Duration.ofHours(72))) shouldBe false
        policy.overdue(AccountDeletionStage.CONTENT_DELETING, requested, requested.plus(Duration.ofHours(72)).plusSeconds(1)) shouldBe true
        policy.overdue(AccountDeletionStage.DRAINING, requested, requested.plus(Duration.ofDays(30))) shouldBe true
    }

    @Test
    fun `an erased deletion is never overdue and the threshold must be positive`() {
        AccountDeletionPolicy(
            Duration.ofHours(1),
        ).overdue(AccountDeletionStage.ERASED, requested, requested.plus(Duration.ofDays(9))) shouldBe
            false
        shouldThrow<IllegalArgumentException> { AccountDeletionPolicy(Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { AccountDeletionPolicy(Duration.ofHours(-1)) }
    }
}
