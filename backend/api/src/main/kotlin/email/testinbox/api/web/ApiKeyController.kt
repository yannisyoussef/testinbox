package email.testinbox.api.web

import email.testinbox.api.auth.AuthAttributes
import email.testinbox.api.auth.requireScope
import email.testinbox.application.port.RevokeApiKeyOutcome
import email.testinbox.application.usecase.ApiKeyPaging
import email.testinbox.application.usecase.ApiKeyQueries
import email.testinbox.application.usecase.CreateApiKey
import email.testinbox.application.usecase.RevokeApiKey
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.tenant.ApiScope
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.UUID

/**
 * Managed API-key lifecycle (ADR-032). Workspace and project are always taken
 * from the authenticated credential, never from the request — there is no
 * field a client could use to create a key somewhere else.
 */
@RestController
@RequestMapping("/v1/api-keys")
class ApiKeyController(
    private val createApiKey: CreateApiKey,
    private val revokeApiKey: RevokeApiKey,
    private val apiKeyQueries: ApiKeyQueries,
) {
    @PostMapping
    fun create(
        @RequestBody body: CreateApiKeyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = AuthAttributes.principal(request)
        actor.requireScope(ApiScope.API_KEYS_MANAGE)

        val requested = body.scopes.orEmpty()
        val resolved = requested.map { it to ApiScope.fromWire(it) }
        val unknown = resolved.filter { it.second == null }.map { it.first }
        if (unknown.isNotEmpty()) {
            return badRequest(request, "Unknown scope(s): ${unknown.sorted().joinToString(", ")}")
        }

        val command =
            CreateApiKey.Command(
                actor = actor,
                name = body.name,
                scopes = resolved.mapNotNull { it.second }.toSet(),
                expiresIn = body.expiresInSeconds?.let(Duration::ofSeconds),
            )
        return when (val result = createApiKey.execute(command)) {
            is CreateApiKey.Result.Created -> {
                ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(
                        CreatedApiKeyDto(
                            apiKey = ApiKeyDto.from(result.apiKey),
                            // The single point in the whole system where the
                            // plaintext is rendered. It is not stored, so this
                            // response cannot be reproduced (ADR-032 §4).
                            key = result.credential.render(),
                        ),
                    )
            }

            CreateApiKey.Result.NoScopesRequested -> {
                badRequest(request, "At least one scope is required")
            }

            is CreateApiKey.Result.NameTooLong -> {
                badRequest(request, "name must be at most ${result.maxLength} characters")
            }

            is CreateApiKey.Result.ExpiryTooSoon -> {
                badRequest(request, "expiresInSeconds must be at least ${result.minimum.toSeconds()}")
            }

            is CreateApiKey.Result.ScopeEscalation -> {
                Problems.respond(
                    Problems.of(
                        HttpStatus.FORBIDDEN,
                        "scope-escalation",
                        "Scope escalation",
                        "A key cannot grant scopes its creator does not hold: " +
                            result.missing
                                .map { it.wire }
                                .sorted()
                                .joinToString(", "),
                        request,
                    ),
                )
            }
        }
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = AuthAttributes.principal(request)
        actor.requireScope(ApiScope.API_KEYS_MANAGE)
        val boundedLimit = ApiKeyPaging.bound(limit)
        val after =
            cursor?.let {
                Cursors.decodeApiKey(it) ?: return badRequest(request, "Malformed cursor")
            }
        val page = apiKeyQueries.list(actor, after, boundedLimit)
        val nextCursor =
            page.lastOrNull()?.takeIf { page.size == boundedLimit }?.let {
                Cursors.encode(it.createdAt, it.id.value)
            }
        return ResponseEntity.ok(ApiKeyPageDto(page.map(ApiKeyDto::from), nextCursor))
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = AuthAttributes.principal(request)
        actor.requireScope(ApiScope.API_KEYS_MANAGE)
        val key = apiKeyQueries.get(actor, ApiKeyId(id)) ?: return notFound(request)
        return ResponseEntity.ok(ApiKeyDto.from(key))
    }

    /**
     * Idempotent: revoking an already-revoked key is `204`. A client retrying
     * after a network failure must never be told its own successful call
     * failed — and `revokedAt` in the metadata already says which case it was.
     */
    @DeleteMapping("/{id}")
    fun revoke(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val actor = AuthAttributes.principal(request)
        actor.requireScope(ApiScope.API_KEYS_MANAGE)
        return when (revokeApiKey.execute(actor, ApiKeyId(id))) {
            RevokeApiKeyOutcome.Revoked, RevokeApiKeyOutcome.AlreadyRevoked -> {
                ResponseEntity.noContent().build<Void>()
            }

            RevokeApiKeyOutcome.NotFound -> {
                notFound(request)
            }
        }
    }

    private fun badRequest(
        request: HttpServletRequest,
        detail: String,
    ): ResponseEntity<*> =
        Problems.respond(
            Problems.of(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail, request),
        )

    /**
     * A key in another workspace is reported identically to one that never
     * existed, so the endpoint cannot be used to probe for credentials
     * elsewhere (cross-tenant lookups are 404, not 403).
     */
    private fun notFound(request: HttpServletRequest): ResponseEntity<*> =
        Problems.respond(
            Problems.of(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such API key", request),
        )
}
