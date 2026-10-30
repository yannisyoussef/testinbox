package email.testinbox.application.usecase

import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.HumanAccountId

/** Revocation is immediately visible to every API process. */
class RevokeHumanSession(
    private val sessions: HumanSessions,
    private val transactions: TransactionRunner,
) {
    fun execute(presented: String?) {
        val credential = SessionCredential.parse(presented) ?: return
        transactions.required {
            val accountId = sessions.findAccountId(credential.verifier()) ?: return@required
            if (sessions.lockAccount(accountId) == null) return@required
            if (sessions.lockSession(accountId, credential.verifier())?.accountId == accountId) sessions.delete(credential.verifier())
        }
    }
}

/** Internal lifecycle operation. Its future HTTP/operator caller must authorize the actor first. */
class RevokeAccountSessions(
    private val sessions: HumanSessions,
    private val transactions: TransactionRunner,
) {
    fun execute(accountId: HumanAccountId) {
        transactions.required {
            if (sessions.lockAccount(accountId) != null) sessions.deleteAll(accountId)
        }
    }
}
