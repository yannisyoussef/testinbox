package email.testinbox.application.port

import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.tenant.ApiScope
import java.time.Instant

/**
 * Security-relevant events that must be reconstructable after the fact
 * (TI-002 §14).
 *
 * This is a **structured** port rather than a logger handed to use cases, for
 * one reason: the fields an audit event may carry are fixed here, in the
 * application layer, where the rule lives. A free-form logger would make
 * "never log a secret" a convention that every future call site has to
 * remember; a closed event type makes it a property of the type system —
 * there is no field to put a secret in.
 *
 * Deliberately absent from every event: the credential, its verifier, the
 * `Authorization` header, and any part of the secret component. The public id
 * IS included: it is non-secret by construction and is what an operator needs
 * to connect an audit line to a credential they can see in the API.
 */
sealed interface AuditEvent {
    val at: Instant
    val workspaceId: WorkspaceId

    /** The credential that performed the action, when one did. */
    val actorApiKeyId: ApiKeyId?

    data class ApiKeyCreated(
        override val at: Instant,
        override val workspaceId: WorkspaceId,
        override val actorApiKeyId: ApiKeyId?,
        val apiKeyId: ApiKeyId,
        val publicId: String,
        val name: String?,
        val scopes: Set<ApiScope>,
        val expiresAt: Instant?,
    ) : AuditEvent

    data class ApiKeyRevoked(
        override val at: Instant,
        override val workspaceId: WorkspaceId,
        override val actorApiKeyId: ApiKeyId?,
        val apiKeyId: ApiKeyId,
        /** True when the key was already revoked — a retry rather than a new revocation. */
        val alreadyRevoked: Boolean,
    ) : AuditEvent

    /**
     * The bootstrap credential's window closed: a request presented it after a
     * managed administrator existed. Recorded because it is the observable
     * moment of the ADR-032 §8 transition, and because a burst of these after
     * a successful cutover means an operator is still shipping the old secret.
     */
    data class BootstrapSuperseded(
        override val at: Instant,
        override val workspaceId: WorkspaceId,
        override val actorApiKeyId: ApiKeyId?,
    ) : AuditEvent
}

interface AuditLog {
    fun record(event: AuditEvent)

    companion object {
        val NOOP: AuditLog =
            object : AuditLog {
                override fun record(event: AuditEvent) = Unit
            }
    }
}
