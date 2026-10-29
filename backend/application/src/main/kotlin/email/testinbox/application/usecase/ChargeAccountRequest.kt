package email.testinbox.application.usecase

import email.testinbox.application.port.AccountRequestBudgets
import email.testinbox.application.port.AccountRequestDecision
import email.testinbox.application.port.AccountRequestPolicy
import email.testinbox.application.port.TransactionRunner

/** Authenticated metadata actions only. Product limits remain keyed on the workspace (ADR-027). */
class ChargeAccountRequest(
    private val authenticate: AuthenticateHumanSession,
    private val budgets: AccountRequestBudgets,
    private val tx: TransactionRunner,
    private val policy: AccountRequestPolicy = AccountRequestPolicy(),
) {
    fun execute(credential: String?): AccountRequestDecision? =
        tx.required {
            val session = authenticate.execute(credential) ?: return@required null
            budgets.charge(session.account.id, policy)
        }
}
