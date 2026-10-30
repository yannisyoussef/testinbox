package email.testinbox.architecture.fixtures

import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.HumanAccountId
import email.testinbox.domain.tenant.HumanWorkspaceOwner

class RogueHumanOwnerFixture {
    fun forge(
        account: HumanAccountId,
        workspace: WorkspaceId,
        project: ProjectId,
    ) = HumanWorkspaceOwner(account, workspace, project)
}
