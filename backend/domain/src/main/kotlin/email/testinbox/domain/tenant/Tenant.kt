package email.testinbox.domain.tenant

import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import java.time.Instant

data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val createdAt: Instant,
)

data class Project(
    val id: ProjectId,
    val workspaceId: WorkspaceId,
    val name: String,
    val createdAt: Instant,
)

/** Permission scopes carried by an API key (ADR-010). */
enum class ApiScope(
    val wire: String,
) {
    INBOXES_WRITE("inboxes:write"),
    MESSAGES_READ("messages:read"),
    ;

    companion object {
        fun fromWire(value: String): ApiScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * An API key as stored: only the SHA-256 hash of the opaque token is
 * persisted (ADR-010 — hashed at rest, never logged).
 */
data class ApiKey(
    val id: ApiKeyId,
    val workspaceId: WorkspaceId,
    val projectId: ProjectId,
    val keyHash: String,
    val scopes: Set<ApiScope>,
    val createdAt: Instant,
    val revokedAt: Instant?,
) {
    fun isUsable(): Boolean = revokedAt == null

    fun hasScope(scope: ApiScope): Boolean = scope in scopes
}
