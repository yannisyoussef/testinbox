package email.testinbox.application.usecase

import email.testinbox.application.Sha256
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.domain.tenant.ApiKey

/**
 * Bearer API-key authentication (ADR-010): the presented opaque token is
 * hashed (SHA-256) and looked up; plaintext keys are never stored or
 * logged. Workspace/project context always derives from the key.
 */
class AuthenticateApiKey(private val apiKeys: ApiKeyRepository) {
    fun authenticate(presentedKey: String): ApiKey? {
        if (presentedKey.isBlank()) return null
        return apiKeys.findActiveByHash(Sha256.hex(presentedKey))?.takeIf { it.isUsable() }
    }
}
