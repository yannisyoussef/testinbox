package email.testinbox.application.port

import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.Project
import email.testinbox.domain.tenant.Workspace

interface ApiKeyRepository {
    /** Looks up a non-revoked key by its SHA-256 hash (keys are never stored in plaintext, ADR-010). */
    fun findActiveByHash(keyHash: String): ApiKey?
}

/** Local/bootstrap provisioning only — idempotent upserts used by dev/test fixtures. */
interface ProvisioningRepository {
    fun ensureWorkspace(workspace: Workspace)

    fun ensureProject(project: Project)

    fun ensureApiKey(apiKey: ApiKey)
}
