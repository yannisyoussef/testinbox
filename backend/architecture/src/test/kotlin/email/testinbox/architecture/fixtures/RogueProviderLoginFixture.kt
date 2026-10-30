package email.testinbox.architecture.fixtures

import email.testinbox.application.usecase.IssueHumanSession
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.ProviderLoginState
import email.testinbox.persistence.JdbcProviderLoginStates

class RogueProviderLoginFixture {
    fun bypassState(
        store: JdbcProviderLoginStates,
        state: ProviderLoginState,
    ) = store.insert(state)

    fun issueFromAnUnverifiedAccount(
        issuer: IssueHumanSession,
        accountId: HumanAccountId,
    ) = issuer.execute(accountId)
}
