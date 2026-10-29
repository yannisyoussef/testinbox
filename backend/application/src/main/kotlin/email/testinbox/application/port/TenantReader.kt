package email.testinbox.application.port

import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace

/**
 * Read side of the tenancy rows (ADR-027 keys everything on the workspace).
 * Every read is by the caller's own workspace id — the one its credential
 * belongs to — so nothing here can be pointed at another tenant.
 */
interface TenantReader {
    fun findWorkspace(id: WorkspaceId): Workspace?

    /** The project only if it belongs to [workspaceId]; a foreign id reads as absent. */
    fun findProject(
        workspaceId: WorkspaceId,
        id: ProjectId,
    ): Project?
}
