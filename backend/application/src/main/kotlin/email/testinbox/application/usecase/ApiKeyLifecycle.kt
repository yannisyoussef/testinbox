package email.testinbox.application.usecase

import email.testinbox.application.Sha256
import email.testinbox.application.port.ApiKeyCursor
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
    }

    fun execute(command: Command): Result {
        if (command.scopes.isEmpty()) return Result.NoScopesRequested
        val missing = command.scopes - command.actor.scopes
        if (missing.isNotEmpty()) return Result.ScopeEscalation(missing)
        if ((command.name?.length ?: 0) > MAX_NAME_LENGTH) return Result.NameTooLong(MAX_NAME_LENGTH)
        if (command.expiresIn != null && command.expiresIn < MIN_EXPIRY) return Result.ExpiryTooSoon(MIN_EXPIRY)

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
                expiresAt = command.expiresIn?.let(now::plus),
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

    companion object {
        const val MAX_NAME_LENGTH = 100

        /**
         * A key that expires in seconds is almost always a mistake in a units
         * conversion, and it would be discovered as an unexplained 401 in
         * someone's pipeline rather than as an error at creation time.
         */
        val MIN_EXPIRY: Duration = Duration.ofSeconds(60)
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

/**
 * Read side of the management API. Every query is workspace-scoped from the
 * authenticated key, so a key belonging to another tenant is simply absent —
 * indistinguishable from one that never existed (anti-enumeration).
 */
class ApiKeyQueries(
    private val apiKeys: ApiKeyRepository,
) {
    fun get(
        actor: ApiKey,
        id: ApiKeyId,
    ): ApiKey? = apiKeys.findById(actor.workspaceId, id)

    fun list(
        actor: ApiKey,
        after: ApiKeyCursor?,
        limit: Int,
    ): List<ApiKey> = apiKeys.listPage(actor.workspaceId, after, limit)
}

/** Shared page-size bounds for the management list endpoint. */
object ApiKeyPaging {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    fun bound(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    fun cursorOf(key: ApiKey): ApiKeyCursor = ApiKeyCursor(key.createdAt, key.id)
}
