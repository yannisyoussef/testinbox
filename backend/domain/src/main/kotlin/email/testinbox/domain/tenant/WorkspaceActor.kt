package email.testinbox.domain.tenant

import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.identity.HumanAccountId

/** Product authority is either a real machine credential or a live human membership. */
sealed interface WorkspaceActor {
    val workspaceId: WorkspaceId
    val projectId: ProjectId
    val scopes: Set<ApiScope>

    fun hasScope(scope: ApiScope): Boolean = scope in scopes
}

/** Constructed only by the live-session membership resolver; never a synthetic ApiKey. */
data class HumanWorkspaceOwner(
    val accountId: HumanAccountId,
    override val workspaceId: WorkspaceId,
    override val projectId: ProjectId,
) : WorkspaceActor {
    override val scopes: Set<ApiScope> = setOf(ApiScope.INBOXES_WRITE, ApiScope.MESSAGES_READ, ApiScope.API_KEYS_MANAGE)
}
