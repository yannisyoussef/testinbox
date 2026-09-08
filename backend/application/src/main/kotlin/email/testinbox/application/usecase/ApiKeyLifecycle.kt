package email.testinbox.application.usecase

import email.testinbox.application.Sha256
import email.testinbox.application.port.ApiKeyMetrics
import email.testinbox.application.port.ApiKeyOperation
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuditLog
import email.testinbox.application.port.RevokeApiKeyOutcome
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyCredential
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Mints a managed credential (ADR-032).
 *
 * The plaintext exists only in the returned [Result.Created] and is never
 * given to the repository, the audit log or the metrics. That is not a
 * convention the call sites have to honour: the value simply does not appear
 * in any argument they receive.
 */
class CreateApiKey(
    private val apiKeys: ApiKeyRepository,
    private val clock: Clock,
    private val audit: AuditLog = AuditLog.NOOP,
    private val metrics: ApiKeyMetrics = ApiKeyMetrics.NOOP,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Command(
        /** The credential performing the operation — the source of workspace, project and ceiling. */
        val actor: ApiKey,
        val name: String?,
        val scopes: Set<ApiScope>,
        val expiresIn: Duration?,
    )

    sealed interface Result {
        /**
         * [credential] is the only place the plaintext ever exists. The caller
         * renders it into the single `201` response and drops it.
         */
        data class Created(
            val apiKey: ApiKey,
            val credential: ApiKeyCredential,
        ) : Result

        data object NoScopesRequested : Result

        /**
         * The caller may not administer credentials at all. Checked here and
         * not only in the HTTP adapter: ADR-027 §3 makes the same point about
         * limits — an authorization rule that lives in a filter protects only
         * the callers that go through that filter.
         */
        data object NotPermitted : Result

        /**
         * The caller asked for a scope it does not itself hold. Without this,
         * a key with only `api-keys:manage` could mint itself an
         * `inboxes:write` key and escalate — the management scope would become
         * a superuser scope by accident.
         */
        data class ScopeEscalation(
            val missing: Set<ApiScope>,
        ) : Result

        data class NameTooLong(
            val maxLength: Int,
        ) : Result

        data class ExpiryTooSoon(
            val minimum: Duration,
        ) : Result

        data class ExpiryTooLong(
            val maximum: Duration,
        ) : Result
    }

    fun execute(command: Command): Result {
        if (!command.actor.hasScope(ApiScope.API_KEYS_MANAGE)) return Result.NotPermitted
        if (command.scopes.isEmpty()) return Result.NoScopesRequested
        val missing = command.scopes - command.actor.scopes
        if (missing.isNotEmpty()) return Result.ScopeEscalation(missing)
        if ((command.name?.length ?: 0) > MAX_NAME_LENGTH) return Result.NameTooLong(MAX_NAME_LENGTH)
        if (command.expiresIn != null && command.expiresIn < MIN_EXPIRY) return Result.ExpiryTooSoon(MIN_EXPIRY)
        if (command.expiresIn != null && command.expiresIn > MAX_EXPIRY) return Result.ExpiryTooLong(MAX_EXPIRY)

        val now = clock.instant()
        val credential = ApiKeyFormat.generate(random)
        val key =
            ApiKey(
                id = ApiKeyId(UUID.randomUUID()),
                workspaceId = command.actor.workspaceId,
                projectId = command.actor.projectId,
                // The secret component only — never the rendered credential.
                keyHash = Sha256.hex(credential.secret),
                scopes = command.scopes,
                createdAt = now,
                revokedAt = null,
                kind = ApiKeyKind.MANAGED,
                publicId = credential.publicId,
                name = command.name?.takeIf { it.isNotBlank() },
                expiresAt = expiryFor(command, now),
                lastUsedAt = null,
                createdByApiKeyId = command.actor.id,
            )
        apiKeys.insert(key)
        metrics.lifecycle(ApiKeyOperation.CREATED)
        audit.record(
            AuditEvent.ApiKeyCreated(
                at = now,
                workspaceId = key.workspaceId,
                actorApiKeyId = command.actor.id,
                apiKeyId = key.id,
                publicId = credential.publicId,
                name = key.name,
                scopes = key.scopes,
                expiresAt = key.expiresAt,
            ),
        )
        return Result.Created(key, credential)
    }

    /**
     * A key may not outlive the credential that minted it.
     *
     * Without this, a deliberately short-lived break-glass administrator is
     * not actually short-lived: whoever holds it — or steals it inside the
     * window — mints a non-expiring successor and the bound is gone. It is the
     * same escalation [Result.ScopeEscalation] prevents, on the other axis.
     */
    private fun expiryFor(
        command: Command,
        now: Instant,
    ): Instant? {
        val requested = command.expiresIn?.let(now::plus)
        val actorExpiry = command.actor.expiresAt
        return when {
            requested == null -> actorExpiry
            actorExpiry == null -> requested
            else -> minOf(requested, actorExpiry)
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 100

        /**
         * A key that expires in seconds is almost always a mistake in a units
         * conversion, and it would be discovered as an unexplained 401 in
         * someone's pipeline rather than as an error at creation time.
         */
        val MIN_EXPIRY: Duration = Duration.ofSeconds(60)

        /**
         * A ceiling mostly so the arithmetic cannot overflow: `now.plus(...)`
         * throws on a `Long.MAX_VALUE` duration, and an uncaught
         * `ArithmeticException` is a 500 with a stack trace — cheap error-log
         * amplification for any authenticated caller, and the wrong answer:
         * an out-of-range lifetime is a bad request.
         */
        val MAX_EXPIRY: Duration = Duration.ofDays(3650)
    }
}

/**
 * Revokes a credential (ADR-032 §5). The row is retained: a compromised key's
 * history is the evidence, and deleting it destroys that at the moment it
 * becomes interesting.
 *
 * Revoking the workspace's last administrative key is **permitted**. Refusing
 * it would protect against lockout at the cost of forbidding the one operation
 * most needed when that key is the compromised one; the bootstrap credential
 * is the break-glass path and reopens exactly then (ADR-032 §8).
 */
class RevokeApiKey(
    private val apiKeys: ApiKeyRepository,
    private val clock: Clock,
    private val audit: AuditLog = AuditLog.NOOP,
    private val metrics: ApiKeyMetrics = ApiKeyMetrics.NOOP,
) {
    fun execute(
        actor: ApiKey,
        id: ApiKeyId,
    ): RevokeApiKeyOutcome {
        val now = clock.instant()
        val outcome = apiKeys.revoke(actor.workspaceId, id, now)
        when (outcome) {
            RevokeApiKeyOutcome.Revoked -> metrics.lifecycle(ApiKeyOperation.REVOKED)
            RevokeApiKeyOutcome.AlreadyRevoked -> metrics.lifecycle(ApiKeyOperation.REVOKE_NOOP)
            RevokeApiKeyOutcome.NotFound -> Unit
        }
        if (outcome != RevokeApiKeyOutcome.NotFound) {
            audit.record(
                AuditEvent.ApiKeyRevoked(
                    at = now,
                    workspaceId = actor.workspaceId,
                    actorApiKeyId = actor.id,
                    apiKeyId = id,
                    alreadyRevoked = outcome == RevokeApiKeyOutcome.AlreadyRevoked,
                ),
            )
        }
        return outcome
    }
}
