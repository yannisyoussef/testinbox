package email.testinbox.application.usecase

import email.testinbox.application.Sha256
import email.testinbox.application.port.ApiKeyMetrics
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuditLog
import email.testinbox.application.port.AuthOutcome
import email.testinbox.application.port.LastUsedRecorder
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.ParsedCredential
import java.security.MessageDigest
import java.time.Clock

/**
 * Bearer API-key authentication (ADR-010, ADR-032 §3).
 *
 * ```
 * parse → public id → row → constant-time verify → state → context
 * ```
 *
 * Two properties are load-bearing and easy to lose in a refactor:
 *
 * 1. **No authorization state is cached.** Every call resolves the credential
 *    from the repository, which is what makes revocation take effect on the
 *    next request on every node without invalidation messaging (ADR-032 §5).
 *    The only cached thing is a `last_used_at` timestamp, which grants nothing.
 * 2. **Workspace, project and scopes come from the row**, never from the
 *    presented token — a token that carried its own scopes would be a
 *    permission the holder could rewrite.
 */
class AuthenticateApiKey(
    private val apiKeys: ApiKeyRepository,
    private val clock: Clock,
    private val metrics: ApiKeyMetrics = ApiKeyMetrics.NOOP,
    private val lastUsed: LastUsedRecorder = LastUsedRecorder.NOOP,
    private val audit: AuditLog = AuditLog.NOOP,
) {
    fun authenticate(presentedKey: String): ApiKey? {
        val result = verify(presentedKey)
        metrics.authCompleted(result.outcome)
        return result.key
    }

    private fun verify(presentedKey: String): Verification {
        val presented = presentedKey.trim()
        if (presented.isEmpty()) return Verification(null, AuthOutcome.MALFORMED)

        return if (ApiKeyFormat.looksLikeCredential(presented)) {
            verifyManaged(presented)
        } else {
            // Anything that is not a TestInbox-shaped token can only be the
            // configured bootstrap credential, which has no format.
            verifyBootstrap(presented)
        }
    }

    private fun verifyManaged(presented: String): Verification {
        val credential =
            when (val parsed = ApiKeyFormat.parse(presented)) {
                is ParsedCredential.Valid -> parsed.credential
                ParsedCredential.ChecksumMismatch -> return Verification(null, AuthOutcome.CHECKSUM_MISMATCH)
                ParsedCredential.UnsupportedVersion -> return Verification(null, AuthOutcome.UNSUPPORTED_VERSION)
                ParsedCredential.Malformed -> return Verification(null, AuthOutcome.MALFORMED)
            }

        val stored =
            apiKeys.findByPublicId(credential.publicId)
                ?: return Verification(null, AuthOutcome.UNKNOWN_KEY)

        // Verify before reporting state. Doing the cheap state checks first
        // would turn the endpoint into an oracle: anyone holding a leaked
        // public id could learn whether that credential is still live without
        // holding the secret at all.
        if (!constantTimeEquals(stored.keyHash, Sha256.hex(credential.secret))) {
            return Verification(null, AuthOutcome.BAD_SECRET)
        }
        // A bootstrap row can never be reached here — findByPublicId returns
        // managed rows only — but the kind is asserted rather than assumed,
        // because the whole bootstrap window (§8) rests on the two paths being
        // disjoint.
        if (stored.kind != ApiKeyKind.MANAGED) return Verification(null, AuthOutcome.UNKNOWN_KEY)

        val now = clock.instant()
        if (stored.isRevoked()) return Verification(null, AuthOutcome.REVOKED)
        if (stored.isExpired(now)) return Verification(null, AuthOutcome.EXPIRED)

        lastUsed.record(stored, now)
        return Verification(stored, AuthOutcome.SUCCESS)
    }

    /**
     * The bootstrap credential (ADR-032 §8) authenticates only while its
     * workspace holds no usable managed administrator. The condition is
     * evaluated **per request**, not at startup: evaluating it once would let
     * a restart silently resurrect the credential.
     */
    private fun verifyBootstrap(presented: String): Verification {
        val stored =
            apiKeys.findBootstrapByHash(Sha256.hex(presented))
                ?: return Verification(null, AuthOutcome.UNKNOWN_KEY)
        val now = clock.instant()
        if (stored.isRevoked()) return Verification(null, AuthOutcome.REVOKED)
        if (stored.isExpired(now)) return Verification(null, AuthOutcome.EXPIRED)
        if (apiKeys.hasUsableManagedAdmin(stored.workspaceId, now)) {
            // The observable moment of the §8 transition. A run of these after
            // a cutover means something is still shipping the old secret.
            audit.record(AuditEvent.BootstrapSuperseded(now, stored.workspaceId, stored.id))
            return Verification(null, AuthOutcome.BOOTSTRAP_SUPERSEDED)
        }
        lastUsed.record(stored, now)
        return Verification(stored, AuthOutcome.BOOTSTRAP)
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private data class Verification(
        val key: ApiKey?,
        val outcome: AuthOutcome,
    )

    companion object {
        /** The scope that closes the bootstrap window — referenced from one place. */
        val ADMIN_SCOPE: ApiScope = ApiScope.API_KEYS_MANAGE
    }
}
