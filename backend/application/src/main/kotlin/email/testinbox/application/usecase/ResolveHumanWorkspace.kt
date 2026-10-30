package email.testinbox.application.usecase

import email.testinbox.application.port.BetaAdmissions
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.tenant.HumanWorkspaceOwner

/** Cookie proof/account state first; workspace/project only from the persisted admission. */
class ResolveHumanWorkspace(
    private val authenticate: AuthenticateHumanSession,
    private val admissions: BetaAdmissions,
    private val tx: TransactionRunner,
) {
    fun execute(credential: String?): HumanWorkspaceOwner? =
        tx.required {
            val session = authenticate.execute(credential) ?: return@required null
            val membership = admissions.findAdmission(session.account.id) ?: return@required null
            HumanWorkspaceOwner(session.account.id, membership.workspaceId, membership.projectId)
        }
}
