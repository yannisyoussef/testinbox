package email.testinbox.application.port

import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace
import java.time.Instant

/** Page position for listing a workspace's keys, newest first. */
data class ApiKeyCursor(
    val createdAt: Instant,
    val id: ApiKeyId,
)

sealed interface RevokeApiKeyOutcome {
    /** The key was usable and is now revoked. */
    data object Revoked : RevokeApiKeyOutcome

    /**
     * The key exists in this workspace and was already revoked. Reported
     * separately from [Revoked] so the audit log can tell a real revocation
     * from a retry, even though the HTTP answer is the same (ADR-032 §5).
     */
    data object AlreadyRevoked : RevokeApiKeyOutcome

    /** No such key **in this workspace** — indistinguishable from another tenant's key. */
    data object NotFound : RevokeApiKeyOutcome
}

interface ApiKeyRepository {
    /**
     * Resolves a managed credential by its non-secret handle. Returns the row
     * whatever its state: revoked and expired keys must be *found* and then
     * refused, or the two failures become indistinguishable in the audit log
     * and in metrics.
     */
    fun findByPublicId(publicId: String): ApiKey?

    /**
     * Resolves a bootstrap credential by the SHA-256 of the whole configured
     * token. Deliberately restricted to [email.testinbox.domain.tenant.ApiKeyKind.BOOTSTRAP]
     * rows: a managed key's verifier hashes a different input, and a lookup
     * that could return either kind would let one path authenticate the other.
     */
    fun findBootstrapByHash(keyHash: String): ApiKey?

    /**
     * True while the workspace holds at least one managed, non-revoked,
     * non-expired key carrying [email.testinbox.domain.tenant.ApiScope.API_KEYS_MANAGE].
     * This is the condition that closes the bootstrap window (ADR-032 §8).
     */
    fun hasUsableManagedAdmin(
        workspaceId: WorkspaceId,
        at: Instant,
    ): Boolean

    fun insert(apiKey: ApiKey)

    /**
     * Managed keys only, scoped to the workspace. Both restrictions are part
     * of the contract rather than a caller's responsibility: a key from
     * another tenant must be indistinguishable from one that never existed,
     * and the management API describes only credentials it can also revoke.
     */
    fun findById(
        workspaceId: WorkspaceId,
        id: ApiKeyId,
    ): ApiKey?

    /**
     * Managed keys of the workspace, newest first. Revoked keys are included —
     * they are retained for audit and their `revokedAt` is what says so.
     *
     * Filtering bootstrap rows here rather than after the fact keeps the page
     * exactly [limit] long, which is what the `nextCursor` contract depends on.
     */
    fun listPage(
        workspaceId: WorkspaceId,
        after: ApiKeyCursor?,
        limit: Int,
    ): List<ApiKey>

    /**
     * Revokes in one guarded statement: [RevokeApiKeyOutcome.Revoked] must be
     * decided by what that statement matched, never by a preceding `SELECT`,
     * or two concurrent revocations would both report a fresh one.
     *
     * Separating [RevokeApiKeyOutcome.AlreadyRevoked] from
     * [RevokeApiKeyOutcome.NotFound] may take a second read; both are "nothing
     * changed", so that read cannot produce a wrong verdict.
     */
    fun revoke(
        workspaceId: WorkspaceId,
        id: ApiKeyId,
        at: Instant,
    ): RevokeApiKeyOutcome

    /**
     * Bounded `last_used_at` refresh (ADR-032 §7). The staleness condition is
     * part of the statement so the decision is made atomically by the database
     * rather than by a read-modify-write race between nodes. Returns whether a
     * row was actually updated, which is what makes the behaviour testable.
     */
    fun touchLastUsed(
        id: ApiKeyId,
        at: Instant,
        onlyIfOlderThan: Instant,
    ): Boolean
}

/** Local/bootstrap provisioning only — idempotent upserts used by dev/test fixtures. */
interface ProvisioningRepository {
    fun ensureWorkspace(workspace: Workspace)

    fun ensureProject(project: Project)

    fun ensureApiKey(apiKey: ApiKey)
}
