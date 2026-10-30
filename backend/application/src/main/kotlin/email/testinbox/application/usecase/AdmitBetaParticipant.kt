package email.testinbox.application.usecase

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import java.time.Clock
import java.util.UUID

/**
 * The only participant workspace provisioner (ADR-036). The HTTP/provider
 * adapter supplies an authenticated account, never a requested workspace.
 * Existing participants may sign in while new admission is paused or full.
 */
class AdmitBetaParticipant(
    private val admissions: BetaAdmissions,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val capacity: email.testinbox.application.storage.CohortStorageReadiness =
        email.testinbox.application.storage
            .CohortStorageReadiness {
                false
            },
) {
    sealed interface Result {
        data class Admitted(
            val admission: BetaAdmission,
            val newlyAdmitted: Boolean,
        ) : Result

        /** Missing, unverified, suspended and deletion-pending accounts are indistinguishable. */
        data object Ineligible : Result

        data object Closed : Result

        data object CapacityUnavailable : Result

        data object Full : Result
    }

    fun execute(accountId: HumanAccountId): Result =
        transactions.required {
            val policy = admissions.lockPolicy()
            val applicant = admissions.lockApplicant(accountId)
            if (applicant == null || !applicant.eligible()) return@required Result.Ineligible

            admissions.findAdmission(accountId)?.let { return@required Result.Admitted(it, newlyAdmitted = false) }
            if (!policy.admissionsOpen) return@required Result.Closed
            if (!policy.capacityReady || !capacity.ready()) return@required Result.CapacityUnavailable
            val place = admissions.nextPlace() ?: return@required Result.Full

            val now = clock.instant()
            val workspace = Workspace(WorkspaceId(UUID.randomUUID()), "My workspace", now)
            val project = Project(ProjectId(UUID.randomUUID()), workspace.id, "Default", now)
            val admission = BetaAdmission(accountId, workspace.id, project.id, place, now)
            admissions.create(admission, workspace, project)
            Result.Admitted(admission, newlyAdmitted = true)
        }
}
