package email.testinbox.application.usecase

import email.testinbox.application.port.EmailLogins
import email.testinbox.application.port.HumanAuthenticationLock
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.EmailLoginChallenge
import java.time.Clock

/** Cleanup is independent of new requests; idle periods must not retain expired login data. */
class PurgeEmailLoginState(
    private val store: EmailLogins,
    private val registration: HumanAuthenticationLock,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    fun execute() {
        transactions.required {
            registration.lock()
            store.purge(clock.instant().minus(EmailLoginChallenge.RETENTION))
        }
    }
}
