package email.testinbox.application.port

import email.testinbox.domain.identity.HumanAccountId
import java.time.Duration

/** One retained row per verified account plus one global row; no source-IP/cardinality table. */
interface AccountRequestBudgets {
    /** Called by the single writer in a transaction, after locking/authenticating the account. */
    fun charge(
        accountId: HumanAccountId,
        policy: AccountRequestPolicy,
    ): AccountRequestDecision
}

data class AccountRequestPolicy(
    val perAccount: Int = 60,
    val global: Int = 3600,
) {
    init {
        require(perAccount in 1..60 && global in 1..3600)
    }
}

data class AccountRequestDecision(
    val allowed: Boolean,
    val retryAfter: Duration = Duration.ZERO,
) {
    init {
        require(allowed || retryAfter >= Duration.ofSeconds(1))
    }
}
