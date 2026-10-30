package email.testinbox.application.usecase

import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.HumanAccount
import java.time.Clock
import java.time.Instant

/** Live account state, never a cookie's claimed account/workspace or a cache. */
class AuthenticateHumanSession(
    private val sessions: HumanSessions,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    data class Authenticated(
        val account: HumanAccount,
        val expiresAt: Instant,
    )

    fun execute(presented: String?): Authenticated? {
        val credential = SessionCredential.parse(presented) ?: return null
        val verifier = credential.verifier()
        return transactions.required {
            val accountId = sessions.findAccountId(verifier) ?: return@required null
            // Consistent account -> session locking also serializes issue,
            // logout, suspension and concurrent idle-window updates.
            val account = sessions.lockAccount(accountId) ?: return@required null
            val session = sessions.lockSession(accountId, verifier) ?: return@required null
            val now = clock.instant()
            if (session.accountId != accountId || !account.eligible() || !session.usableAt(now)) {
                sessions.delete(verifier)
                return@required null
            }
            sessions.touch(verifier, now)
            Authenticated(account, session.expiresAt)
        }
    }
}
