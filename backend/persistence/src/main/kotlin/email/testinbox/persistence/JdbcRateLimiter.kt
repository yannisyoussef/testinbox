package email.testinbox.persistence

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
 */
class JdbcRateLimiter(
    private val jdbc: JdbcClient,
    transactionManager: PlatformTransactionManager,
    private val policies: (RateCategory) -> RatePolicy,
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
        val policy = policies(category)
        return ownTransaction.execute { consume(workspaceId, category, inboxId, policy) }!!
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
