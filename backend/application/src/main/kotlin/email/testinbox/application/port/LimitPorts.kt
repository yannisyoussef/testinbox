package email.testinbox.application.port

import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.QuotaDimension
import email.testinbox.domain.limits.RateCategory
import java.time.Duration
import java.time.Instant

/**
 * Outcome of charging one token against a workspace's bucket (ADR-027).
 * Carries what the HTTP adapter needs to render `RateLimit-*` headers
 * without knowing how the limit is stored.
 */
data class RateDecision(
    val category: RateCategory,
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    /** Present only on refusal; already rounded to a whole second (>= 1s). */
    val retryAfter: Duration?,
)

/**
 * Workspace-scoped request rate limiting. Backed by shared persistence, not
 * per-node memory: a tenant alternating between API nodes must not get N×
 * its allowance (ADR-027).
 */
interface RateLimiter {
    /**
     * Charges one token. [inboxId] narrows the scope below the workspace —
     * used for `INGEST`, so a flood against one guessed address cannot
     * consume the whole workspace's inbound budget.
     *
     * Implementations must commit the spend independently of the request it
     * governs: a spend that rolled back with a failing handler would make
     * every request an attacker can force to fail free of charge.
     */
    fun tryConsume(
        workspaceId: WorkspaceId,
        category: RateCategory,
        inboxId: InboxId? = null,
    ): RateDecision

    /**
     * Whole tokens the workspace holds in [category] right now, without
     * spending one. Null when the implementation cannot say (a fake, or a
     * bucket never seen — which is full). A usage read, never a decision.
     */
    fun peek(
        workspaceId: WorkspaceId,
        category: RateCategory,
    ): Long? = null
}

/**
 * Read side of quota enforcement. Both counts are **derived from the rows
 * that actually exist**, never from a maintained counter — a counter cannot
 * be decremented from an `ON DELETE CASCADE`, so it would drift upward until
 * every workspace wedged at "quota exceeded" (ADR-027 §4).
 */
data class WorkspaceStorageQuota(
    val usedBytes: Long,
    val payloadLimitBytes: Long?,
)

interface WorkspaceQuotaState {
    /**
     * Serializes admission decisions for this workspace for the remainder of
     * the current transaction, closing the check-then-act race where two
     * concurrent callers both observe free capacity.
     *
     * Must be the FIRST statement of any transaction that will also touch
     * `inbox` or `message` rows: acquiring it after those rows are locked
     * creates a lock-order cycle with the hard-delete path.
     */
    fun guardAdmission(workspaceId: WorkspaceId)

    /** Inboxes in a routable state (`ACTIVE`/`EXPIRING`). */
    fun activeInboxCount(workspaceId: WorkspaceId): Long

    /** Raw MIME plus extracted attachment bytes currently retained. */
    fun storedBytes(workspaceId: WorkspaceId): Long

    /** Same own-workspace ledger/reservations and persisted share the usage read reports. */
    fun storageQuota(workspaceId: WorkspaceId): WorkspaceStorageQuota = WorkspaceStorageQuota(storedBytes(workspaceId), null)
}

/** A held concurrent-wait slot; releasing it frees capacity for the tenant. */
interface WaitSlot : AutoCloseable {
    override fun close()
}

/**
 * Concurrent long-poll admission (ADR-027 §7). Slots are claimed by database
 * constraint rather than by a lock — the same pattern ADR-021 uses for exact
 * addresses — so wait admission never contends with the inbound write path.
 */
interface WaitSlots {
    /**
     * Claims one of [maxConcurrent] slots for [workspaceId], or returns null
     * when the tenant already holds them all. [leaseFor] bounds the claim so a
     * node crash cannot leak a slot permanently; the deadline is computed by
     * the database, not by a node clock, so skew cannot free a live slot.
     */
    fun acquire(
        workspaceId: WorkspaceId,
        maxConcurrent: Long,
        leaseFor: Duration,
    ): WaitSlot?

    /** Reclaims slots whose holder died; returns how many were freed. */
    fun reapExpired(): Int

    /** Slots the workspace holds right now: live leases only, a dead holder's lease counts for nothing. A usage read. */
    fun held(workspaceId: WorkspaceId): Long
}

/**
 * Metrics for limit decisions (ADR-027 §14). Kept as a port so `application`
 * stays framework-free (ADR-024) and so labels are chosen here, where the
 * bounded-cardinality rule can be enforced: never a workspace id, api key,
 * inbox id, or address.
 */
interface LimitMetrics {
    fun rateDecision(
        category: RateCategory,
        allowed: Boolean,
    ) {}

    fun quotaRejected(dimension: QuotaDimension) {}

    companion object {
        val NOOP: LimitMetrics = object : LimitMetrics {}
    }
}
