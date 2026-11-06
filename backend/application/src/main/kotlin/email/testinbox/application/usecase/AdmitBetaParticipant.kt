package email.testinbox.application.usecase

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.BetaParticipantCap
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import java.time.Clock
import java.util.UUID

/**
 * The only participant workspace provisioner (ADR-036). The HTTP/provider
 * adapter supplies an authenticated account, never a requested workspace.
 * Existing participants may sign in while new admission is paused or full.
 *
 * [cap] is the configured participant cap N (`testinbox.beta.max-participants`).
 * The database's own guard stays at the hard thirty; N is enforced here, in the
 * same transaction and after the same cohort lock ([BetaAdmissions.lockPolicy])
 * that already serialises every admission, so the place read by
 * [BetaAdmissions.nextPlace] cannot be taken concurrently. A place beyond N is
 * the existing `Full` outcome. Lowering N evicts nobody: an admitted account
 * still gets its admission back, only NEW admissions stop.
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
    private val cap: BetaParticipantCap = BetaParticipantCap.HARD_LIMIT,
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
            // Under the cohort lock taken above: no other admission can consume a place
            // between this read and the insert, so N is never exceeded.
            val place = admissions.nextPlace()?.takeIf(cap::admits) ?: return@required Result.Full

            val now = clock.instant()
            val workspace = Workspace(WorkspaceId(UUID.randomUUID()), "My workspace", now)
            val project = Project(ProjectId(UUID.randomUUID()), workspace.id, "Default", now)
            val admission = BetaAdmission(accountId, workspace.id, project.id, place, now)
            admissions.create(admission, workspace, project)
            Result.Admitted(admission, newlyAdmitted = true)
        }
}
