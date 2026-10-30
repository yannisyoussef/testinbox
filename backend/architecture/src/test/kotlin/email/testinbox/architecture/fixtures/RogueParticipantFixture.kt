package email.testinbox.architecture.fixtures

import email.testinbox.domain.identity.BetaAdmission
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import email.testinbox.persistence.JdbcBetaAdmissions

/** Deliberately invalid: a concrete adapter must not bypass the admission use case. */
class RogueParticipantFixture {
    fun provision(
        adapter: JdbcBetaAdmissions,
        admission: BetaAdmission,
        workspace: Workspace,
        project: Project,
    ) {
        adapter.create(admission, workspace, project)
    }
}
