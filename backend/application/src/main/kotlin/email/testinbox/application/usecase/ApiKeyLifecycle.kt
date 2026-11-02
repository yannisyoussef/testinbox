package email.testinbox.application.usecase

import email.testinbox.application.Sha256
import email.testinbox.application.idempotency.ApiKeySnapshot
import email.testinbox.application.idempotency.Idempotency
import email.testinbox.application.idempotency.IdempotencyRequest
import email.testinbox.application.idempotency.RequestFingerprint
import email.testinbox.application.port.ApiKeyMetrics
import email.testinbox.application.port.ApiKeyOperation
import email.testinbox.application.port.ApiKeyRepository
import email.testinbox.application.port.AuditEvent
import email.testinbox.application.port.AuditLog
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.RevokeApiKeyOutcome
import email.testinbox.application.port.WorkspaceEligibility
import email.testinbox.application.port.WorkspaceInactiveException
import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.application.port.WorkspaceStatus
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.idempotency.IdempotentOperation
import email.testinbox.domain.limits.ParticipantKeyInventory
import email.testinbox.domain.limits.QuotaExceeded
import email.testinbox.domain.tenant.ApiKey
import email.testinbox.domain.tenant.ApiKeyCredential
import email.testinbox.domain.tenant.ApiKeyFormat
import email.testinbox.domain.tenant.ApiKeyKind
import email.testinbox.domain.tenant.ApiScope
import email.testinbox.domain.tenant.HumanWorkspaceOwner
import email.testinbox.domain.tenant.WorkspaceActor
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
    private val idempotency: Idempotency = Idempotency.disabled(),
    /**
     * The existing ADR-027 workspace admission guard. Key minting takes it,
     * like inbox creation, so the account-lifecycle writer (which takes it
     * first) serializes with every key writer of the workspace and the
     * participant inventory bound is a check-then-act under one lock.
     */
    private val guard: WorkspaceQuotaState,
    private val eligibility: WorkspaceEligibility,
) {
    data class Command(
        /** The credential performing the operation — the source of workspace, project and ceiling. */
        val actor: WorkspaceActor,
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

        /**
         * This exact request already created a credential (ADR-033 §8).
         *
         * **Duplicate suppression, not idempotent replay** — the deliberate
         * deviation from TI-003 §15. The secret was returned once and nothing
         * in the system retains it (ADR-032 §4), so the honest answer names the
         * credential rather than pretending to reproduce it: revoke it and mint
         * again under a fresh key.
         */
        data class AlreadyCreated(
            val apiKeyId: ApiKeyId,
            val publicId: String,
        ) : Result

        /**
         * A participant workspace reached its fixed key inventory
         * ([email.testinbox.domain.limits.ParticipantKeyInventory]): `409
         * quota-exceeded` with the caller's own dimension only.
         */
        data class InventoryExhausted(
            val exceeded: QuotaExceeded,
        ) : Result

        data object IdempotencyKeyReused : Result

        data object IdempotencyInProgress : Result

        data object IdempotencyReplayUnavailable : Result
    }

    @JvmOverloads
    fun execute(
        command: Command,
        request: IdempotencyRequest? = null,
    ): Result {
        if (!command.actor.hasScope(ApiScope.API_KEYS_MANAGE)) return Result.NotPermitted
        if (request != null &&
            (
                request.actorApiKeyId != (command.actor as? ApiKey)?.id ||
                    request.actorHumanAccountId != (command.actor as? HumanWorkspaceOwner)?.accountId
            )
        ) {
            return Result.NotPermitted
        }
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
                createdByApiKeyId = (command.actor as? ApiKey)?.id,
                createdByHumanAccountId = (command.actor as? HumanWorkspaceOwner)?.accountId,
            )
        val scope =
            IdempotencyScope(key.workspaceId, key.projectId, IdempotentOperation.CREATE_API_KEY)
        val result =
            idempotency.around(
                request = request,
                scope = { scope },
                fingerprint = { RequestFingerprint.of(command) },
                outcomes =
                    Idempotency.Outcomes(
                        replay = { snapshot ->
                            ApiKeySnapshot.toCreated(snapshot)?.let { (id, publicId) ->
                                Result.AlreadyCreated(id, publicId)
                            } ?: Result.IdempotencyReplayUnavailable
                        },
                        keyReused = { Result.IdempotencyKeyReused },
                        inProgress = { Result.IdempotencyInProgress },
                        snapshotOf = { r ->
                            (r as? Result.Created)?.let { ApiKeySnapshot.of(it.apiKey.id, it.credential.publicId) }
                        },
                    ),
                // ADR-033 §4a. `AlreadyCreated` names a credential for the
                // caller to revoke, so it must never name one minted by a
                // different credential in the same workspace.
                replayBoundToActor = true,
            ) { mint(key, credential, now) }

        // AFTER the transaction, deliberately. Emitting inside it would let a
        // rolled-back claim log that a credential was created that does not
        // exist — in the audit trail ADR-032 §5 relies on as evidence — because
        // neither SLF4J nor Micrometer participates in a rollback.
        if (result is Result.Created) {
            metrics.lifecycle(ApiKeyOperation.CREATED)
            audit.record(
                AuditEvent.ApiKeyCreated(
                    at = now,
                    workspaceId = key.workspaceId,
                    actorApiKeyId = (command.actor as? ApiKey)?.id,
                    actorHumanAccountId = (command.actor as? HumanWorkspaceOwner)?.accountId,
                    apiKeyId = key.id,
                    publicId = credential.publicId,
                    name = key.name,
                    scopes = key.scopes,
                    expiresAt = key.expiresAt,
                ),
            )
        }
        return result
    }

    /** Inside the claim's transaction: the workspace guard, lifecycle, the participant inventory, then the one insert. */
    private fun mint(
        key: ApiKey,
        credential: ApiKeyCredential,
        now: Instant,
    ): Result {
        guard.guardAdmission(key.workspaceId)
        // Rechecked under the guard the lifecycle writer also takes: a
        // request authenticated just before a suspension mints nothing after it.
        val status = eligibility.status(key.workspaceId)
        if (status == WorkspaceStatus.INACTIVE_PARTICIPANT) throw WorkspaceInactiveException()
        if (status == WorkspaceStatus.ACTIVE_PARTICIPANT) {
            val inventory = apiKeys.managedInventory(key.workspaceId, now)
            ParticipantKeyInventory.refusal(inventory.usable, inventory.retained)?.let { return Result.InventoryExhausted(it) }
        }
        apiKeys.insert(key)
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
        val actorExpiry = (command.actor as? ApiKey)?.expiresAt
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
 * Proof that the current database transaction's role holds the
 * account-lifecycle operator authority (ACCOUNT-LIFECYCLE.md "Operator
 * authority and writes"). Only the lifecycle use cases construct one, after
 * the store confirmed the authority INSIDE the write transaction, and they
 * close it when that transaction ends. No HTTP adapter, API key or request
 * field can produce one.
 */
class OperatorAuthorization internal constructor() {
    internal var open: Boolean = true
        private set

    internal fun close() {
        open = false
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
        actor: WorkspaceActor,
        id: ApiKeyId,
    ): RevokeApiKeyOutcome {
        if (!actor.hasScope(ApiScope.API_KEYS_MANAGE)) return RevokeApiKeyOutcome.NotPermitted
        val now = clock.instant()
        val outcome = apiKeys.revoke(actor.workspaceId, id, now)
        when (outcome) {
            RevokeApiKeyOutcome.Revoked -> metrics.lifecycle(ApiKeyOperation.REVOKED)
            RevokeApiKeyOutcome.AlreadyRevoked -> metrics.lifecycle(ApiKeyOperation.REVOKE_NOOP)
            RevokeApiKeyOutcome.NotFound, RevokeApiKeyOutcome.NotPermitted -> Unit
        }
        if (outcome == RevokeApiKeyOutcome.Revoked || outcome == RevokeApiKeyOutcome.AlreadyRevoked) {
            audit.record(
                AuditEvent.ApiKeyRevoked(
                    at = now,
                    workspaceId = actor.workspaceId,
                    actorApiKeyId = (actor as? ApiKey)?.id,
                    actorHumanAccountId = (actor as? HumanWorkspaceOwner)?.accountId,
                    apiKeyId = id,
                    alreadyRevoked = outcome == RevokeApiKeyOutcome.AlreadyRevoked,
                ),
            )
        }
        return outcome
    }

    /**
     * The explicit operator revocation of ACCOUNT-LIFECYCLE.md: every managed
     * key of the workspace, permanently, in the caller's lifecycle
     * transaction. Resuming the account later never un-revokes them. Returns
     * how many keys this call revoked; the caller reports it after commit.
     */
    fun revokeWorkspaceForLifecycle(
        authority: OperatorAuthorization,
        workspaceId: email.testinbox.domain.WorkspaceId,
    ): Int {
        check(authority.open) { "operator authority is bound to the transaction that verified it" }
        return apiKeys.revokeAllManaged(workspaceId, clock.instant())
    }

    /**
     * Restore reconciliation only (owner decision 2026-10-30): every managed
     * key of every workspace, participant, owner and monitoring alike, in the
     * caller's reconciliation transaction. A backup is never current
     * authorization, so no restored credential authenticates again; the
     * owner's way back in is the configured bootstrap credential, which
     * reopens exactly because no usable managed administrator remains
     * (ADR-032 §8). Returns how many keys this call revoked.
     */
    fun revokeEveryManagedForRestore(authority: OperatorAuthorization): Int {
        check(authority.open) { "operator authority is bound to the transaction that verified it" }
        return apiKeys.revokeEveryManaged(clock.instant())
    }
}
