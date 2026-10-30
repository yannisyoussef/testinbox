package email.testinbox.application.usecase

import email.testinbox.application.port.BetaAdmissionControls
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.identity.BetaCohortPolicy

/**
 * The sole writer of the operator admission pause (ADR-036 §5).
 *
 * Pausing stops NEW admissions only: [AdmitBetaParticipant] returns its
 * existing `Closed` outcome under the cohort row lock, so no admission can
 * commit after a pause commits. Existing participants, sessions, API keys,
 * inboxes and waitlist entries are untouched. It is distinct from the
 * sign-in enablement flag (configuration) and from per-account suspension.
 * Neither direction changes `capacity_ready`, the fixed cap or any place.
 */
class ControlBetaAdmissions(
    private val controls: BetaAdmissionControls,
    private val transactions: TransactionRunner,
) {
    data class Status(
        val admissionsOpen: Boolean,
        val capacityReady: Boolean,
        val consumedPlaces: Int,
    ) {
        val maxPlaces: Int = BetaCohortPolicy.MAX_PARTICIPANTS

        /** The `storage-budget` precondition: both cohort switches closed. */
        val poolChangesAllowed: Boolean get() = !admissionsOpen && !capacityReady
    }

    data class Transition(
        val before: Status,
        val after: Status,
    ) {
        val changed: Boolean get() = before.admissionsOpen != after.admissionsOpen
    }

    class NotAuthorized : IllegalStateException("the database role is not authorized to change cohort admission")

    fun status(): Status = transactions.required { snapshot(controls.readPolicy()) }

    fun pause(): Transition = set(open = false)

    fun resume(): Transition = set(open = true)

    private fun set(open: Boolean): Transition =
        transactions.required {
            val policy = controls.lockPolicy()
            // Rechecked under the row lock, not trusted from the connection string.
            if (!controls.operatorAuthorized()) throw NotAuthorized()
            val before = snapshot(policy)
            if (policy.admissionsOpen != open) controls.setAdmissionsOpen(open)
            Transition(before, before.copy(admissionsOpen = open))
        }

    private fun snapshot(policy: BetaCohortPolicy) = Status(policy.admissionsOpen, policy.capacityReady, controls.consumedPlaces())
}
