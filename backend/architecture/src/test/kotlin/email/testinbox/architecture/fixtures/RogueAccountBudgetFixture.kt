package email.testinbox.architecture.fixtures

import email.testinbox.application.port.AccountRequestPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.persistence.JdbcAccountRequestBudgets

class RogueAccountBudgetFixture(
    private val budgets: JdbcAccountRequestBudgets,
) {
    fun bypass(id: HumanAccountId) = budgets.charge(id, AccountRequestPolicy())
}
