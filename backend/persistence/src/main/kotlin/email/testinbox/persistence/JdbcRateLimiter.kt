package email.testinbox.persistence

import email.testinbox.application.port.RateCharge
import email.testinbox.application.port.RateDecision
import email.testinbox.application.port.RateLimiter
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy
import email.testinbox.domain.limits.TokenBucket
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

/**
 * Token-bucket rate limiting over PostgreSQL (ADR-027 §4).
 *
 * Two properties are load-bearing and easy to lose:
 *
 * 1. **PostgreSQL is the only clock.** Elapsed time comes from comparing the
 *    stored `updated_at` against the server's `now()`, read in the same
 *    statement. Using an API node's wall clock would reintroduce, one layer
 *    down, exactly the defect that rules out per-node counters: a fast node
 *    grants extra tokens and a slow node writes a past timestamp for the next
 *    node to refill against.
 * 2. **The spend commits on its own.** It runs in a REQUIRES_NEW transaction,
 *    so a request that fails afterwards is still charged. Otherwise anything
 *    an attacker can force to error is free.
 *
 * The arithmetic itself lives in the framework-free domain [TokenBucket] and
 * is exercised by its own property tests; this adapter owns only atomicity
 * and the time source.
 *
 * Suppression note: `jdbc` and `Snapshot` are both used, in `consume` below.
 * Detekt runs here without type resolution and does not follow usages reached
 * only through a private function invoked from inside a lambda (the
 * TransactionTemplate callback), so it reports them as dead. Narrowly
 * suppressed rather than reshaping working code for the analyser; drop this if
 * the Detekt task ever gains type resolution.
 */
@Suppress("UnusedPrivateProperty", "UnusedPrivateClass")
class JdbcRateLimiter(
    private val jdbc: JdbcClient,
    transactionManager: PlatformTransactionManager,
    private val policies: (RateCategory, Boolean) -> RatePolicy,
) : RateLimiter {
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private data class Snapshot(
        val tokens: Double,
        val updatedAt: Instant,
        val serverNow: Instant,
    )

    override fun tryConsume(
        workspaceId: WorkspaceId,
        category: RateCategory,
        inboxId: InboxId?,
    ): RateDecision {
        val policy = policies(category, inboxId != null)
        return ownTransaction.execute { consume(workspaceId, category, inboxId, policy) }!!
    }

    /**
     * Read-only: no row is created, locked or updated. A bucket never seen is
     * full, so the policy's capacity is the honest answer for it.
     */
    override fun peek(
        workspaceId: WorkspaceId,
        category: RateCategory,
    ): Long? {
        val policy = policies(category, false)
        val snapshot =
            jdbc
                .sql(
                    """
                    SELECT tokens, updated_at, now() AS server_now
                      FROM rate_bucket
                     WHERE workspace_id = :workspaceId AND category = :category AND inbox_id IS NULL
                    """.trimIndent(),
                ).param("workspaceId", workspaceId.value)
                .param("category", category.name)
                .query { rs, _ ->
                    Snapshot(
                        tokens = rs.getDouble("tokens"),
                        updatedAt = Timestamps.fromDb(rs, "updated_at")!!,
                        serverNow = Timestamps.fromDb(rs, "server_now")!!,
                    )
                }.optional()
                .orElse(null) ?: return policy.capacity
        return TokenBucket.available(TokenBucket.State(snapshot.tokens, snapshot.updatedAt), policy, snapshot.serverNow)
    }

    /**
     * Gives back [charges] in ONE `REQUIRES_NEW` transaction (owner decision
     * 2026-11-06, ADR-027 §4 as amended). Per bucket, under the same
     * `FOR UPDATE` row lock a spend takes, the bucket is refilled to the
     * transaction's `now()` exactly as a spend refills it, the bucket's count
     * of charges is added, and the result is clamped at capacity
     * ([TokenBucket.refund]). So:
     *
     * - **concurrency**: a refund serializes with every spend and every other
     *   refund of the same bucket on its row lock. Buckets are locked in one
     *   global order (workspace, category, inbox with the workspace bucket
     *   first), and a spend holds one row at a time, so two refunds cannot
     *   deadlock each other or a spend;
     * - **overflow**: `tokens ≤ capacity` after every refund, and a refund
     *   adds at most the number of charges it was handed;
     * - **all or nothing**: every bucket of the attempt commits together, or
     *   none does and the exception says so. The caller never retries.
     *
     * A bucket row that no longer exists (its workspace or inbox was deleted,
     * `ON DELETE CASCADE`) is skipped, never recreated: there is nothing left
     * to give back to. `updated_at` never moves backwards.
     */
    override fun refund(charges: List<RateCharge>) {
        if (charges.isEmpty()) return
        val perBucket =
            charges
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedWith(
                    compareBy<Map.Entry<RateCharge, Int>>(
                        { it.key.workspaceId.value },
                        { it.key.category.name },
                        { it.key.inboxId?.value },
                    ),
                )
        ownTransaction.executeWithoutResult {
            perBucket.forEach { (bucket, tokens) -> refundBucket(bucket, tokens) }
        }
    }

    private fun refundBucket(
        bucket: RateCharge,
        tokens: Int,
    ) {
        val policy = policies(bucket.category, bucket.inboxId != null)
        val snapshot =
            jdbc
                .sql(
                    """
                    SELECT tokens, updated_at, now() AS server_now
                      FROM rate_bucket
                     WHERE workspace_id = :workspaceId AND category = :category
                       AND inbox_id IS NOT DISTINCT FROM :inboxId
                       FOR UPDATE
                    """.trimIndent(),
                ).param("workspaceId", bucket.workspaceId.value)
                .param("category", bucket.category.name)
                .param("inboxId", bucket.inboxId?.value)
                .query { rs, _ ->
                    Snapshot(
                        tokens = rs.getDouble("tokens"),
                        updatedAt = Timestamps.fromDb(rs, "updated_at")!!,
                        serverNow = Timestamps.fromDb(rs, "server_now")!!,
                    )
                }.optional()
                .orElse(null) ?: return
        val refunded =
            TokenBucket.refund(
                TokenBucket.State(snapshot.tokens, snapshot.updatedAt),
                policy,
                snapshot.serverNow,
                tokens,
            )
        // GREATEST: a refund that waited on the row lock behind a spend which
        // committed a later updated_at refilled nothing (elapsed clamps at 0)
        // and must not move updated_at back, or the next spend would refill
        // the same interval twice.
        jdbc
            .sql(
                """
                UPDATE rate_bucket SET tokens = :tokens, updated_at = GREATEST(updated_at, now())
                 WHERE workspace_id = :workspaceId AND category = :category
                   AND inbox_id IS NOT DISTINCT FROM :inboxId
                """.trimIndent(),
            ).param("tokens", refunded.tokens)
            .param("workspaceId", bucket.workspaceId.value)
            .param("category", bucket.category.name)
            .param("inboxId", bucket.inboxId?.value)
            .update()
    }

    private fun consume(
        workspaceId: WorkspaceId,
        category: RateCategory,
        inboxId: InboxId?,
        policy: RatePolicy,
    ): RateDecision {
        // Create the bucket full on first sight. A losing racer simply finds the
        // row on the locking read below, so no exception path is needed.
        jdbc
            .sql(
                """
                INSERT INTO rate_bucket (workspace_id, category, inbox_id, tokens, updated_at)
                VALUES (:workspaceId, :category, :inboxId, :capacity, now())
                ON CONFLICT (workspace_id, category, inbox_id) DO NOTHING
                """.trimIndent(),
            ).param("workspaceId", workspaceId.value)
            .param("category", category.name)
            .param("inboxId", inboxId?.value)
            .param("capacity", policy.capacity.toDouble())
            .update()

        val snapshot =
            jdbc
                .sql(
                    """
                    SELECT tokens, updated_at, now() AS server_now
                      FROM rate_bucket
                     WHERE workspace_id = :workspaceId AND category = :category
                       AND inbox_id IS NOT DISTINCT FROM :inboxId
                       FOR UPDATE
                    """.trimIndent(),
                ).param("workspaceId", workspaceId.value)
                .param("category", category.name)
                .param("inboxId", inboxId?.value)
                .query { rs, _ ->
                    Snapshot(
                        tokens = rs.getDouble("tokens"),
                        updatedAt = Timestamps.fromDb(rs, "updated_at")!!,
                        serverNow = Timestamps.fromDb(rs, "server_now")!!,
                    )
                }.single()

        val decision =
            TokenBucket.spend(
                TokenBucket.State(snapshot.tokens, snapshot.updatedAt),
                policy,
                snapshot.serverNow,
            )

        jdbc
            .sql(
                """
                UPDATE rate_bucket SET tokens = :tokens, updated_at = now()
                 WHERE workspace_id = :workspaceId AND category = :category
                   AND inbox_id IS NOT DISTINCT FROM :inboxId
                """.trimIndent(),
            ).param("tokens", decision.state.tokens)
            .param("workspaceId", workspaceId.value)
            .param("category", category.name)
            .param("inboxId", inboxId?.value)
            .update()

        return RateDecision(
            category = category,
            allowed = decision.allowed,
            limit = policy.capacity,
            remaining = decision.remaining,
            // RFC 9110 Retry-After is integer seconds; a sub-second value would
            // round to 0 and invite a hot retry loop.
            retryAfter = decision.retryAfter?.let { atLeastOneSecond(it) },
        )
    }

    /**
     * Whole seconds, rounded up, never zero. RFC 9110 grants Retry-After only
     * integer seconds; truncating a sub-second wait to 0 would invite a hot
     * retry loop, and truncating 1.6s to 1s would advertise a retry that is
     * still too early.
     */
    private fun atLeastOneSecond(duration: Duration): Duration {
        val seconds = Math.ceilDiv(duration.toNanos(), NANOS_PER_SECOND)
        return Duration.ofSeconds(maxOf(1L, seconds))
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
