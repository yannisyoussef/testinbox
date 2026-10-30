package email.testinbox.application.port

import email.testinbox.domain.identity.HumanAccount
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.identity.HumanSession
import java.time.Instant

/**
 * All mutations require one transaction and the account row lock first.
 * Looking up an account ID grants no authority: the caller must lock and
 * re-read both account and session before authorizing anything.
 */
interface HumanSessions {
    fun findAccountId(verifier: String): HumanAccountId?

    fun lockAccount(accountId: HumanAccountId): HumanAccount?

    fun lockSession(
        accountId: HumanAccountId,
        verifier: String,
    ): HumanSession?

    /** Leave room for one newly issued session, including expired-row cleanup. */
    fun makeRoom(
        accountId: HumanAccountId,
        now: Instant,
    )

    fun insert(session: HumanSession)

    fun touch(
        verifier: String,
        at: Instant,
    )

    fun delete(verifier: String)

    fun deleteAll(accountId: HumanAccountId)
}
