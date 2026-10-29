package email.testinbox.application.port

import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.domain.identity.HumanAccountId
import java.time.Instant

/** All writes run under the fixed cohort lock, through ManageBetaWaitlist. */
interface BetaWaitlist {
    fun find(accountId: HumanAccountId): BetaWaitlistEntry?

    fun nextPlace(maximum: Int): Int?

    fun insert(entry: BetaWaitlistEntry)

    fun remove(accountId: HumanAccountId)

    fun purge(now: Instant)
}
