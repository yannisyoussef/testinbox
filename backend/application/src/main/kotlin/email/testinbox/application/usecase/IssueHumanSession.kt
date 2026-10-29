package email.testinbox.application.usecase

import email.testinbox.application.identity.SessionCredential
import email.testinbox.application.port.HumanSessions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanSession
import java.time.Clock
import java.time.Instant

/** Called after identity verification; never from a client-supplied account ID. */
class IssueHumanSession(
    private val sessions: HumanSessions,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    class Issued(
        val credential: SessionCredential,
        val expiresAt: Instant,
    ) {
        override fun toString(): String = "IssuedHumanSession(credential=[REDACTED], expiresAt=$expiresAt)"
    }

    fun execute(
        accountId: HumanAccountId,
        replacing: String? = null,
    ): Issued? =
        transactions.required {
            val account = sessions.lockAccount(accountId)
            if (account?.eligible() != true) return@required null
            // Only replace a session belonging to this verified account.
            SessionCredential.parse(replacing)?.let { previous ->
                if (sessions.lockSession(accountId, previous.verifier())?.accountId == accountId) sessions.delete(previous.verifier())
            }
            val now = clock.instant()
            val credential = SessionCredential.generate()
            val expiry = now.plus(HumanSession.ABSOLUTE_LIFETIME)
            sessions.makeRoom(accountId, now)
            sessions.insert(HumanSession(credential.verifier(), accountId, now, expiry, now))
            Issued(credential, expiry)
        }
}
