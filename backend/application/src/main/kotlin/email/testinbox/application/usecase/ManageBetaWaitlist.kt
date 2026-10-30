package email.testinbox.application.usecase

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.BetaWaitlist
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.BetaWaitlistEntry
import email.testinbox.domain.identity.BetaWaitlistPolicy
import email.testinbox.domain.identity.HumanAccountId
import java.time.Clock

class ManageBetaWaitlist(
    private val admissions: BetaAdmissions,
    private val store: BetaWaitlist,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    enum class Action { READ, JOIN, WITHDRAW }

    sealed interface Result {
        data class Waiting(
            val entry: BetaWaitlistEntry,
        ) : Result

        data object NotWaiting : Result

        data object Admitted : Result

        data object Unavailable : Result

        data object Ineligible : Result
    }

    fun execute(
        accountId: HumanAccountId,
        action: Action,
        policy: BetaWaitlistPolicy? = null,
    ): Result =
        tx.required {
            admissions.lockPolicy()
            val applicant = admissions.lockApplicant(accountId)
            if (applicant == null || !applicant.eligible()) return@required Result.Ineligible
            val now = clock.instant()
            store.purge(now)
            if (action == Action.WITHDRAW) {
                store.remove(accountId)
                return@required Result.NotWaiting
            }
            if (admissions.findAdmission(accountId) != null) return@required Result.Admitted
            store.find(accountId)?.let { return@required Result.Waiting(it) }
            if (action == Action.READ) return@required Result.NotWaiting
            val selectedPolicy = policy ?: return@required Result.Unavailable
            val place = store.nextPlace(selectedPolicy.maximum) ?: return@required Result.Unavailable
            val entry = BetaWaitlistEntry(accountId, place, now, now.plus(selectedPolicy.retention))
            store.insert(entry)
            Result.Waiting(entry)
        }

    fun purge() {
        tx.required {
            admissions.lockPolicy()
            store.purge(clock.instant())
        }
    }
}
