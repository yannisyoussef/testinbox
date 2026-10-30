package email.testinbox.persistence

import email.testinbox.application.port.TenantReader
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/** Tenancy rows by id. Both statements select by the caller's workspace id and nothing wider. */
@Repository
class JdbcTenantReader(
    private val jdbc: JdbcClient,
) : TenantReader {
    override fun findWorkspace(id: WorkspaceId): Workspace? =
        jdbc
            .sql("SELECT id, name, created_at FROM workspace WHERE id = :id")
            .param("id", id.value)
            .query { rs, _ ->
                Workspace(
                    id = WorkspaceId(rs.getObject("id", UUID::class.java)),
                    name = rs.getString("name"),
                    createdAt = Timestamps.fromDb(rs, "created_at")!!,
                )
            }.optional()
            .orElse(null)

    override fun findProject(
        workspaceId: WorkspaceId,
        id: ProjectId,
    ): Project? =
        jdbc
            .sql("SELECT id, workspace_id, name, created_at FROM project WHERE id = :id AND workspace_id = :workspaceId")
            .param("id", id.value)
            .param("workspaceId", workspaceId.value)
            .query { rs, _ ->
                Project(
                    id = ProjectId(rs.getObject("id", UUID::class.java)),
                    workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
                    name = rs.getString("name"),
                    createdAt = Timestamps.fromDb(rs, "created_at")!!,
                )
            }.optional()
            .orElse(null)
}
