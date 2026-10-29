package email.testinbox.application.port

import email.testinbox.domain.identity.AdmissionApplicant
import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.identity.BetaCohortPolicy
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace

/**
 * ADR-036 participant provisioning, not bootstrap fixtures. Every operation
 * runs inside the one admission transaction. Lock order: cohort, then account.
 * Implementations serialize on the database, never a process-local mutex.
 */
interface BetaAdmissions {
    fun lockPolicy(): BetaCohortPolicy

    fun lockApplicant(id: HumanAccountId): AdmissionApplicant?

    fun findAdmission(id: HumanAccountId): BetaAdmission?

    /** Consumed places include deleted-account tombstones. Null means full. */
    fun nextPlace(): Int?

    /** Creates all three rows together; only AdmitBetaParticipant calls this. */
    fun create(
        admission: BetaAdmission,
        workspace: Workspace,
        project: Project,
    )
}
