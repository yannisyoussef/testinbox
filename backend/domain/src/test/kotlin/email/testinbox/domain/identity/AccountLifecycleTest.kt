package email.testinbox.domain.identity

import email.testinbox.domain.identity.AccountLifecycle.Decision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AccountLifecycleTest {
    private fun decide(
        from: HumanAccountState,
        action: AccountLifecycleAction,
    ) = AccountLifecycle.decide(from, action)

    @Test
    fun `suspension and deletion leave active, and only suspension can be resumed`() {
        decide(HumanAccountState.ACTIVE, AccountLifecycleAction.SUSPEND) shouldBe Decision.Change(HumanAccountState.SUSPENDED)
        decide(HumanAccountState.SUSPENDED, AccountLifecycleAction.RESUME) shouldBe Decision.Change(HumanAccountState.ACTIVE)
        decide(HumanAccountState.ACTIVE, AccountLifecycleAction.DELETE) shouldBe Decision.Change(HumanAccountState.DELETION_PENDING)
        decide(HumanAccountState.SUSPENDED, AccountLifecycleAction.DELETE) shouldBe Decision.Change(HumanAccountState.DELETION_PENDING)
    }

    @Test
    fun `repeating a command is idempotent so the operator can re-check draining`() {
        decide(HumanAccountState.SUSPENDED, AccountLifecycleAction.SUSPEND) shouldBe Decision.Unchanged(HumanAccountState.SUSPENDED)
        decide(HumanAccountState.ACTIVE, AccountLifecycleAction.RESUME) shouldBe Decision.Unchanged(HumanAccountState.ACTIVE)
        decide(HumanAccountState.DELETION_PENDING, AccountLifecycleAction.DELETE) shouldBe
            Decision.Unchanged(HumanAccountState.DELETION_PENDING)
    }

    @Test
    fun `deletion pending is terminal - neither suspension nor resume can leave it`() {
        for (action in listOf(AccountLifecycleAction.SUSPEND, AccountLifecycleAction.RESUME)) {
            val decision = decide(HumanAccountState.DELETION_PENDING, action)
            (decision is Decision.Refused) shouldBe true
            decision.to shouldBe HumanAccountState.DELETION_PENDING
        }
    }

    @Test
    fun `every reachable state except active revokes access`() {
        HumanAccountState.entries.forEach { from ->
            AccountLifecycleAction.entries.forEach { action ->
                val to = decide(from, action).to
                AccountLifecycle.revokesAccess(to) shouldBe (to != HumanAccountState.ACTIVE)
            }
        }
        AccountLifecycle.revokesAccess(HumanAccountState.ACTIVE) shouldBe false
    }

    @Test
    fun `only resuming a suspension returns an account to active`() {
        val reachesActive =
            HumanAccountState.entries.flatMap { from ->
                AccountLifecycleAction.entries.mapNotNull { action ->
                    (from to action).takeIf { decide(from, action) == Decision.Change(HumanAccountState.ACTIVE) }
                }
            }
        reachesActive shouldBe listOf(HumanAccountState.SUSPENDED to AccountLifecycleAction.RESUME)
    }
}
