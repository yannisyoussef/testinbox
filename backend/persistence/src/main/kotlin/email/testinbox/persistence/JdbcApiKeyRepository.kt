package email.testinbox.persistence

import email.testinbox.application.port.ApiKeyCursor
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.ProvisioningRepository
import email.testinbox.application.port.RevokeApiKeyOutcome
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

private const val COLUMNS =
    "id, workspace_id, project_id, key_hash, scopes, created_at, revoked_at, " +
        "kind, public_id, name, expires_at, last_used_at, created_by_api_key_id"

@Repository
class JdbcApiKeyRepository(
    private val jdbc: JdbcClient,
) : ApiKeyRepository,
    ProvisioningRepository {
    /**
     * Managed rows only. A bootstrap row has no public id, so it cannot be
     * reached here — but the predicate is written out rather than relied upon,
     * because the disjointness of the two authentication paths is what the
     * ADR-032 §8 bootstrap window rests on.
     */
    override fun findByPublicId(publicId: String): ApiKey? =
        jdbc
            .sql("SELECT $COLUMNS FROM api_key WHERE public_id = :publicId AND kind = 'MANAGED'")
            .param("publicId", publicId)
            .query { rs, _ -> mapApiKey(rs) }
            .optional()
            .orElse(null)

    override fun findBootstrapByHash(keyHash: String): ApiKey? =
        jdbc
            .sql("SELECT $COLUMNS FROM api_key WHERE key_hash = :keyHash AND kind = 'BOOTSTRAP'")
            .param("keyHash", keyHash)
            .query { rs, _ -> mapApiKey(rs) }
            .optional()
            .orElse(null)

    override fun hasUsableManagedAdmin(
        workspaceId: WorkspaceId,
        at: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                SELECT 1 FROM api_key
                 WHERE workspace_id = :workspaceId
                   AND kind = 'MANAGED'
                   AND revoked_at IS NULL
                   -- `>=`, matching ApiKey.isExpired: `expiresAt` is the last
                   -- moment of validity, not the first moment of expiry. `>`
                   -- left a one-microsecond window where the domain called an
                   -- admin key usable and this query did not — and this query
                   -- is what holds the bootstrap door shut.
                   AND (expires_at IS NULL OR expires_at >= :at)
                   AND :adminScope = ANY (scopes)
                 LIMIT 1
                """.trimIndent(),
            ).param("workspaceId", workspaceId.value)
            .param("at", Timestamps.toDb(at))
            .param("adminScope", ApiScope.API_KEYS_MANAGE.wire)
            .query(Int::class.javaObjectType)
            .optional()
            .isPresent

    override fun insert(apiKey: ApiKey) {
        jdbc
            .sql(
                """
                INSERT INTO api_key ($COLUMNS)
                VALUES (:id, :workspaceId, :projectId, :keyHash, :scopes, :createdAt, :revokedAt,
                        :kind, :publicId, :name, :expiresAt, :lastUsedAt, :createdBy)
                """.trimIndent(),
            ).param("id", apiKey.id.value)
            .param("workspaceId", apiKey.workspaceId.value)
            .param("projectId", apiKey.projectId.value)
            .param("keyHash", apiKey.keyHash)
            .param("scopes", apiKey.scopes.map { it.wire }.toTypedArray())
            .param("createdAt", Timestamps.toDb(apiKey.createdAt))
            .param("revokedAt", apiKey.revokedAt?.let(Timestamps::toDb))
            .param("kind", apiKey.kind.name)
            .param("publicId", apiKey.publicId)
            .param("name", apiKey.name)
            .param("expiresAt", apiKey.expiresAt?.let(Timestamps::toDb))
            .param("lastUsedAt", apiKey.lastUsedAt?.let(Timestamps::toDb))
            .param("createdBy", apiKey.createdByApiKeyId?.value)
            .update()
    }

    override fun findById(
        workspaceId: WorkspaceId,
        id: ApiKeyId,
    ): ApiKey? =
        jdbc
            .sql(
                "SELECT $COLUMNS FROM api_key " +
                    "WHERE id = :id AND workspace_id = :workspaceId AND kind = 'MANAGED'",
            ).param("id", id.value)
            .param("workspaceId", workspaceId.value)
            .query { rs, _ -> mapApiKey(rs) }
            .optional()
            .orElse(null)

    override fun listPage(
        workspaceId: WorkspaceId,
        after: ApiKeyCursor?,
        limit: Int,
    ): List<ApiKey> {
        // Newest first, tie-broken by id so the ordering is total and the
        // cursor cannot skip or repeat rows created in the same instant.
        val keyset =
            if (after == null) {
                ""
            } else {
                "AND (created_at, id) < (:afterCreatedAt, :afterId) "
            }
        val query =
            jdbc
                .sql(
                    "SELECT $COLUMNS FROM api_key " +
                        "WHERE workspace_id = :workspaceId AND kind = 'MANAGED' " +
                        keyset +
                        "ORDER BY created_at DESC, id DESC LIMIT :limit",
                ).param("workspaceId", workspaceId.value)
                .param("limit", limit)
        if (after != null) {
            query
                .param("afterCreatedAt", Timestamps.toDb(after.createdAt))
                .param("afterId", after.id.value)
        }
        return query.query { rs, _ -> mapApiKey(rs) }.list()
    }

    /**
     * The **effect** is one guarded statement: a `SELECT` then `UPDATE` would
     * let two concurrent revocations each report a fresh revocation.
     *
     * Distinguishing "already revoked" from "not yours" does take a second
     * read, and that read is not atomic with the update — deliberately, and it
     * cannot mislead: `Revoked` is decided entirely by what the `UPDATE`
     * matched, and the follow-up only separates two answers that are both
     * "nothing changed". It stays workspace-scoped so it cannot become an
     * existence oracle.
     */
    override fun revoke(
        workspaceId: WorkspaceId,
        id: ApiKeyId,
        at: Instant,
    ): RevokeApiKeyOutcome {
        val updated =
            jdbc
                .sql(
                    """
                    UPDATE api_key SET revoked_at = :at
                     WHERE id = :id AND workspace_id = :workspaceId
                       AND kind = 'MANAGED' AND revoked_at IS NULL
                    """.trimIndent(),
                ).param("at", Timestamps.toDb(at))
                .param("id", id.value)
                .param("workspaceId", workspaceId.value)
                .update()
        if (updated > 0) return RevokeApiKeyOutcome.Revoked
        // Nothing changed: either the key is not ours, or it was already
        // revoked. Only that second lookup can tell the two apart, and it must
        // stay workspace-scoped so it cannot become an existence oracle.
        val exists = findById(workspaceId, id) != null
        return if (exists) RevokeApiKeyOutcome.AlreadyRevoked else RevokeApiKeyOutcome.NotFound
    }

    override fun touchLastUsed(
        id: ApiKeyId,
        at: Instant,
        onlyIfOlderThan: Instant,
    ): Boolean =
        jdbc
            .sql(
                """
                UPDATE api_key SET last_used_at = :at
                 WHERE id = :id
                   AND (last_used_at IS NULL OR last_used_at < :threshold)
                """.trimIndent(),
            ).param("at", Timestamps.toDb(at))
            .param("id", id.value)
            .param("threshold", Timestamps.toDb(onlyIfOlderThan))
            .update() > 0

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

    /**
     * Upserts the scope set rather than doing nothing on conflict. An
     * environment that already holds a bootstrap row provisioned before
     * `api-keys:manage` existed would otherwise never gain it, and so could
     * never mint the first managed credential — the bootstrap key would be
     * permanently unable to hand over (ADR-032 §8). Nothing else is updated:
     * the verifier is the conflict key, and identity must not move.
     */
    override fun ensureApiKey(apiKey: ApiKey) {
        jdbc
            .sql(
                """
                INSERT INTO api_key ($COLUMNS)
                VALUES (:id, :workspaceId, :projectId, :keyHash, :scopes, :createdAt, :revokedAt,
                        :kind, :publicId, :name, :expiresAt, :lastUsedAt, :createdBy)
                ON CONFLICT (key_hash) DO UPDATE SET scopes = EXCLUDED.scopes
                """.trimIndent(),
            ).param("id", apiKey.id.value)
            .param("workspaceId", apiKey.workspaceId.value)
            .param("projectId", apiKey.projectId.value)
            .param("keyHash", apiKey.keyHash)
            .param("scopes", apiKey.scopes.map { it.wire }.toTypedArray())
            .param("createdAt", Timestamps.toDb(apiKey.createdAt))
            .param("revokedAt", apiKey.revokedAt?.let(Timestamps::toDb))
            .param("kind", apiKey.kind.name)
            .param("publicId", apiKey.publicId)
            .param("name", apiKey.name)
            .param("expiresAt", apiKey.expiresAt?.let(Timestamps::toDb))
            .param("lastUsedAt", apiKey.lastUsedAt?.let(Timestamps::toDb))
            .param("createdBy", apiKey.createdByApiKeyId?.value)
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
            kind = ApiKeyKind.valueOf(rs.getString("kind")),
            publicId = rs.getString("public_id"),
            name = rs.getString("name"),
            expiresAt = Timestamps.fromDb(rs, "expires_at"),
            lastUsedAt = Timestamps.fromDb(rs, "last_used_at"),
            createdByApiKeyId = rs.getObject("created_by_api_key_id", UUID::class.java)?.let(::ApiKeyId),
        )
    }
}
