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

/**
 * Permission scopes carried by an API key (ADR-010, ADR-032 §9).
 *
 * Deliberately coarse. The property that matters is that an ordinary CI
 * credential cannot mint or revoke credentials; endpoint-shaped scopes grow
 * without bound and end up copied rather than reasoned about.
 */
enum class ApiScope(
    val wire: String,
) {
    INBOXES_WRITE("inboxes:write"),
    MESSAGES_READ("messages:read"),

    /**
     * The whole key-lifecycle surface. Separately controlled precisely so a
     * key handed to a CI system does not carry it.
     */
    API_KEYS_MANAGE("api-keys:manage"),
    ;

    companion object {
        fun fromWire(value: String): ApiScope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * How a credential came to exist. Load-bearing rather than descriptive: a
 * BOOTSTRAP key authenticates only while its workspace holds no usable
 * MANAGED key with [ApiScope.API_KEYS_MANAGE] (ADR-032 §8), and is never
 * returned or created by the management API.
 */
enum class ApiKeyKind {
    MANAGED,
    BOOTSTRAP,
}

/**
 * An API key as stored. Only a verifier is persisted, never the credential
 * (ADR-010 hashed at rest; ADR-032 §4 unrecoverable by construction).
 *
 * [keyHash] is `SHA-256` of the *secret component* for a [ApiKeyKind.MANAGED]
 * key and of the whole configured token for a [ApiKeyKind.BOOTSTRAP] one —
 * the two kinds are authenticated by different paths and never compared
 * against each other.
 *
 * `LongParameterList` is suppressed rather than relaxed project-wide: that rule
 * exists to catch a *function* taking too many collaborators, and this is a
 * record — every parameter is one column of one row. Grouping three of them
 * behind a wrapper to satisfy a counter would make the persistence mapping
 * less obvious, not more, and the rule still guards real functions.
 */
@Suppress("LongParameterList")
data class ApiKey(
    val id: ApiKeyId,
    val workspaceId: WorkspaceId,
    val projectId: ProjectId,
    val keyHash: String,
    val scopes: Set<ApiScope>,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val kind: ApiKeyKind = ApiKeyKind.MANAGED,
    /** Non-secret handle; absent for bootstrap keys, which have no format. */
    val publicId: String? = null,
    val name: String? = null,
    val expiresAt: Instant? = null,
    /** Approximate to within the coalescing interval (ADR-032 §7). Never an authorization input. */
    val lastUsedAt: Instant? = null,
    /** The credential that minted this one; absent for a bootstrap key or a workspace's first. */
    val createdByApiKeyId: ApiKeyId? = null,
) {
    fun isRevoked(): Boolean = revokedAt != null

    fun isExpired(at: Instant): Boolean = expiresAt?.isBefore(at) == true

    /**
     * The only usability predicate, and it requires a clock on purpose. A
     * clock-free `isUsable()` existed and had to consider revocation alone;
     * once keys can expire, such an overload is a permanent invitation to
     * authenticate an expired credential by picking the shorter name.
     */
    fun isUsableAt(at: Instant): Boolean = !isRevoked() && !isExpired(at)

    fun hasScope(scope: ApiScope): Boolean = scope in scopes

    /**
     * The verifier is not secret-derived-but-harmless: it is the one field
     * that must never be rendered anywhere. Redact it in the data class
     * `toString` so a log line, an exception message or a debugger dump
     * cannot leak it by accident.
     */
    override fun toString(): String =
        "ApiKey(id=$id, publicId=$publicId, kind=$kind, workspaceId=$workspaceId, " +
            "projectId=$projectId, scopes=$scopes, name=$name, createdAt=$createdAt, " +
            "expiresAt=$expiresAt, revokedAt=$revokedAt, lastUsedAt=$lastUsedAt, " +
            "createdByApiKeyId=$createdByApiKeyId, keyHash=<redacted>)"
}
