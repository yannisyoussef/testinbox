package email.testinbox.domain.identity

import java.time.Duration
import java.time.Instant

/** Opaque protocol material is encrypted; no provider access/refresh token belongs here. */
data class ProviderLoginState(
    val stateHash: String,
    val browserHash: String,
    val sourceHash: String,
    val provider: LoginProvider,
    val encryptedRequest: String,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require(provider != LoginProvider.EMAIL)
        require(listOf(stateHash, browserHash, sourceHash).all { it.matches(Regex("[0-9a-f]{64}")) })
        require(encryptedRequest.length in 1..16_384)
        require(expiresAt > createdAt && expiresAt <= createdAt.plus(LIFETIME))
    }

    override fun toString(): String = "ProviderLoginState(provider=$provider, request=[REDACTED])"

    companion object {
        val LIFETIME: Duration = Duration.ofMinutes(10)
        val RETENTION: Duration = Duration.ofHours(24)
    }
}
