package email.testinbox.application.idempotency

import email.testinbox.application.Sha256
import email.testinbox.application.port.IdempotencyScope
import email.testinbox.application.port.IdempotencySnapshot
import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.InboxId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.IdempotencyKey
import email.testinbox.domain.inbox.AddressMode
import email.testinbox.domain.inbox.Inbox
import email.testinbox.domain.inbox.InboxState
import java.time.Instant
import java.util.UUID

/** What the adapter hands inward when a caller supplied `Idempotency-Key`. */
data class IdempotencyRequest(
    val key: IdempotencyKey,
    /** The credential presenting the request; binds key-creation replay (ADR-033 §4a). */
    val actorApiKeyId: ApiKeyId?,
)

object IdempotencyKeys {
    /**
     * Salted with the scope (ADR-033 §4a). ADR-032 §2 rejected a pepper for a
     * 260-bit random secret; that reasoning does not transfer. Idempotency keys
     * are low-entropy and structured — CI job ids, branch names, test names —
     * so an unsalted digest is confirmable by anyone with read access, which is
     * the whole reason §11 gives for hashing them. It also stops one value
     * being correlatable across workspaces.
     */
    fun hash(
        scope: IdempotencyScope,
        key: IdempotencyKey,
    ): String = Sha256.hex("${scope.workspaceId.value}|${scope.operation.wire}|${key.value}")
}

/**
 * The application's own record of a committed inbox creation (ADR-033 §6).
 *
 * A flat string map, not a rendered response: storing the response would freeze
 * the representation against ADR-015 for the whole retention window and would
 * put an adapter type in the application layer. The use case reconstructs its
 * own `Result` and the existing controller renders it, so rendering stays in
 * one place and the status code is derived rather than stored.
 */
object InboxSnapshot {
    const val VERSION = 1

    fun of(inbox: Inbox): IdempotencySnapshot =
        IdempotencySnapshot(
            VERSION,
            mapOf(
                "inboxId" to inbox.id.value.toString(),
                "workspaceId" to inbox.workspaceId.value.toString(),
                "projectId" to inbox.projectId.value.toString(),
                "address" to inbox.address,
                "addressMode" to inbox.addressMode.name,
                "state" to inbox.state.name,
                "createdAt" to inbox.createdAt.toString(),
                "expiresAt" to inbox.expiresAt.toString(),
            ),
        )

    /**
     * Null when this artifact cannot read the snapshot — a newer artifact wrote
     * a version it does not know, which is reachable under ADR-028 rollback.
     * The caller answers `idempotency-replay-unavailable` rather than guessing
     * or re-executing.
     */
    fun toInbox(snapshot: IdempotencySnapshot): Inbox? {
        if (snapshot.version != VERSION) return null
        val p = snapshot.payload
        return runCatching {
            Inbox(
                id = InboxId(UUID.fromString(p.getValue("inboxId")!!)),
                workspaceId = WorkspaceId(UUID.fromString(p.getValue("workspaceId")!!)),
                projectId = ProjectId(UUID.fromString(p.getValue("projectId")!!)),
                address = p.getValue("address")!!,
                addressMode = AddressMode.valueOf(p.getValue("addressMode")!!),
                // The state AT CREATION. A replay is a record of what the
                // request created, not an assertion that it still exists
                // (ADR-033 §6) — a client that replays and then fetches may
                // get 404 once the inbox has expired.
                state = InboxState.valueOf(p.getValue("state")!!),
                createdAt = Instant.parse(p.getValue("createdAt")!!),
                expiresAt = Instant.parse(p.getValue("expiresAt")!!),
            )
        }.getOrNull()
    }
}

/**
 * A committed key creation. Metadata only — never the credential, and never
 * the verifier. ADR-032 §4 is structural, so there is nothing here to relax.
 */
object ApiKeySnapshot {
    const val VERSION = 1

    fun of(
        apiKeyId: ApiKeyId,
        publicId: String,
    ): IdempotencySnapshot =
        IdempotencySnapshot(
            VERSION,
            mapOf("apiKeyId" to apiKeyId.value.toString(), "publicId" to publicId),
        )

    fun toCreated(snapshot: IdempotencySnapshot): Pair<ApiKeyId, String>? {
        if (snapshot.version != VERSION) return null
        return runCatching {
            ApiKeyId(UUID.fromString(snapshot.payload.getValue("apiKeyId")!!)) to
                snapshot.payload.getValue("publicId")!!
        }.getOrNull()
    }
}
