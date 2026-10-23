package email.testinbox.application.port

import email.testinbox.domain.ApiKeyId
import email.testinbox.domain.ProjectId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.idempotency.IdempotentOperation
import java.time.Duration
import java.time.Instant

/**
 * What a record is keyed by (ADR-033 §4a). The project is carried but is NOT
 * part of uniqueness — it is part of the fingerprint instead, so a key reused
 * across projects is a deterministic conflict rather than a surprise resource
 * in the wrong one.
 */
data class IdempotencyScope(
    val workspaceId: WorkspaceId,
    val projectId: ProjectId,
    val operation: IdempotentOperation,
)

/** The application's own record of what a committed mutation produced. */
data class IdempotencySnapshot(
    val version: Int,
    val payload: Map<String, String?>,
)

sealed interface ClaimOutcome {
    /** This request owns the key. It must perform the mutation and then [IdempotencyRecords.complete]. */
    data object Claimed : ClaimOutcome

    /** An identical request already committed; replay [snapshot]. */
    data class Replay(
        val snapshot: IdempotencySnapshot,
        val claimedByApiKeyId: ApiKeyId?,
    ) : ClaimOutcome

    /** The key is bound to a different logical request. Terminal — retrying with it can never succeed. */
    data object FingerprintMismatch : ClaimOutcome

    /**
     * A concurrent claim is still running and did not finish within the bounded
     * wait. Transient: retrying with the same key is the correct action, and
     * will replay once the first request commits.
     */
    data object Contended : ClaimOutcome
}

/**
 * The idempotency claim (ADR-033 §2).
 *
 * [claim] must be called **inside** the same transaction as the mutation it
 * guards. That is the whole design: a duplicate blocks on the unique index
 * until this transaction commits or aborts, so an uncommitted claim is
 * invisible by construction and there is no `IN_PROGRESS` state, no lease and
 * no takeover logic to get wrong.
 *
 * It follows that only a committed success is ever recorded — a rejection
 * rolls the claim back with it (§4) — and that "committed mutation, missing
 * record" cannot occur.
 */
interface IdempotencyRecords {
    /**
     * Claims [scope] for [fingerprint], waiting at most [waitFor] for a
     * concurrent claim to resolve.
     *
     * [waitFor] is a policy expressed as a duration; how it is imposed is the
     * adapter's business. It bounds **this statement only** — an implementation
     * that let it bound the rest of the transaction would abort unrelated,
     * correct work (ADR-033 §3).
     */
    fun claim(
        scope: IdempotencyScope,
        keyHash: String,
        fingerprint: String,
        claimedByApiKeyId: ApiKeyId?,
        now: Instant,
        expiresAt: Instant,
        waitFor: Duration,
    ): ClaimOutcome

    /** Records what the mutation produced. Same transaction as [claim] and the mutation. */
    fun complete(
        scope: IdempotencyScope,
        keyHash: String,
        snapshot: IdempotencySnapshot,
    )

    /** Retention sweep. Batched, so one tick cannot become an unbounded delete. */
    fun deleteExpired(
        now: Instant,
        batchSize: Int,
    ): Int
}
