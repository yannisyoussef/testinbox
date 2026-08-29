package email.testinbox.persistence

import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.ProvisioningRepository
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcApiKeyRepository(
    private val jdbc: JdbcClient,
) : ApiKeyRepository,
    ProvisioningRepository {
    override fun findActiveByHash(keyHash: String): ApiKey? =
        jdbc
            .sql("SELECT * FROM api_key WHERE key_hash = :keyHash AND revoked_at IS NULL")
            .param("keyHash", keyHash)
            .query { rs, _ -> mapApiKey(rs) }
            .optional()
            .orElse(null)

    override fun ensureWorkspace(workspace: Workspace) {
        jdbc
            .sql(
                """
                INSERT INTO workspace (id, name, created_at) VALUES (:id, :name, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).param("id", workspace.id.value)
            .param("name", workspace.name)
            .param("createdAt", Timestamps.toDb(workspace.createdAt))
            .update()
    }

    override fun ensureProject(project: Project) {
        jdbc
            .sql(
                """
                INSERT INTO project (id, workspace_id, name, created_at)
                VALUES (:id, :workspaceId, :name, :createdAt)
                ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
            ).param("id", project.id.value)
            .param("workspaceId", project.workspaceId.value)
            .param("name", project.name)
            .param("createdAt", Timestamps.toDb(project.createdAt))
            .update()
    }

    override fun ensureApiKey(apiKey: ApiKey) {
        jdbc
            .sql(
                """
                INSERT INTO api_key (id, workspace_id, project_id, key_hash, scopes, created_at, revoked_at)
                VALUES (:id, :workspaceId, :projectId, :keyHash, :scopes, :createdAt, :revokedAt)
                ON CONFLICT (key_hash) DO NOTHING
                """.trimIndent(),
            ).param("id", apiKey.id.value)
            .param("workspaceId", apiKey.workspaceId.value)
            .param("projectId", apiKey.projectId.value)
            .param("keyHash", apiKey.keyHash)
            .param("scopes", apiKey.scopes.map { it.wire }.toTypedArray())
            .param("createdAt", Timestamps.toDb(apiKey.createdAt))
            .param("revokedAt", apiKey.revokedAt?.let(Timestamps::toDb))
            .update()
    }

    private fun mapApiKey(rs: ResultSet): ApiKey {
        @Suppress("UNCHECKED_CAST")
        val scopes = (rs.getArray("scopes").array as Array<String>).mapNotNull(ApiScope::fromWire).toSet()
        return ApiKey(
            id = ApiKeyId(rs.getObject("id", UUID::class.java)),
            workspaceId = WorkspaceId(rs.getObject("workspace_id", UUID::class.java)),
            projectId = ProjectId(rs.getObject("project_id", UUID::class.java)),
            keyHash = rs.getString("key_hash"),
            scopes = scopes,
            createdAt = Timestamps.fromDb(rs, "created_at")!!,
            revokedAt = Timestamps.fromDb(rs, "revoked_at"),
        )
    }
}
