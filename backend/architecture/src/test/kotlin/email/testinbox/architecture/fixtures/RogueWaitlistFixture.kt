package email.testinbox.architecture.fixtures

import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.persistence.JdbcBetaWaitlist

class RogueWaitlistFixture {
    fun bypass(
        store: JdbcBetaWaitlist,
        entry: BetaWaitlistEntry,
    ) {
        store.insert(entry)
    }
}
