package email.testinbox.persistence

import email.testinbox.application.port.WaitSlot
import email.testinbox.application.port.WaitSlots
import email.testinbox.domain.WorkspaceId
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

/**
 * Concurrent-wait admission by database constraint (ADR-027 §7) — the same
 * pattern ADR-021 uses for exact addresses. A claim either wins the unique
 * index on `(workspace_id, slot_index)` or loses it; no lock is taken, so
 * wait admission never contends with the inbound write path.
 *
 * Row growth is bounded by construction: a slot index at or beyond the
 * configured ceiling is never inserted, so the limiter's own storage cannot
 * be turned into a denial-of-service vector.
 */
@Repository
class JdbcWaitSlots(
    private val jdbc: JdbcClient,
) : WaitSlots {
    override fun acquire(
        workspaceId: WorkspaceId,
        maxConcurrent: Long,
        leaseFor: Duration,
    ): WaitSlot? {
        // Reclaim this workspace's dead slots first: without it a crashed node
        // would permanently shrink the tenant's allowance.
        releaseExpired(workspaceId)
        // Bounded: the allocator enumerates candidate indexes, so an
        // astronomically large ceiling must not become an unbounded scan.
        val ceiling = minOf(maxConcurrent, MAX_ENUMERATED_SLOTS)

        // Single statement: pick the lowest free index and insert it. Two racers
        // can choose the same index, and the unique index rejects one of them —
        // hence the bounded retry rather than a lock.
        repeat(ACQUIRE_ATTEMPTS) {
            val id = UUID.randomUUID()
            val inserted =
                jdbc
                    .sql(
                        """
                        INSERT INTO wait_lease (id, workspace_id, slot_index, acquired_at, expires_at)
                        SELECT :id, :workspaceId, candidate, now(), now() + make_interval(secs => :leaseSeconds)
                          FROM generate_series(0, :maxIndex) AS candidate
                         WHERE NOT EXISTS (
                                   SELECT 1 FROM wait_lease
                                    WHERE workspace_id = :workspaceId AND slot_index = candidate
                               )
                         ORDER BY candidate
                         LIMIT 1
                        ON CONFLICT (workspace_id, slot_index) DO NOTHING
                        """.trimIndent(),
                    ).param("id", id)
                    .param("workspaceId", workspaceId.value)
                    .param("maxIndex", ceiling - 1)
                    .param("leaseSeconds", leaseFor.toSeconds().toDouble())
                    .update()
            if (inserted == 1) return Lease(id)
            // Zero rows means either every slot is taken, or a racer took the one
            // we chose. Distinguish by re-counting; only a full table is a refusal.
            if (heldSlots(workspaceId) >= ceiling) return null
        }
        return null
    }

    /**
     * Expiry is evaluated by PostgreSQL, never by a node clock. A node running
     * fast would otherwise delete leases that are still live — silently
     * removing the concurrency ceiling for whichever workspace it served.
     */
    override fun reapExpired(): Int =
        jdbc
            .sql("DELETE FROM wait_lease WHERE expires_at <= now()")
            .update()
            .also { if (it > 0) log.info("wait_lease_reaped count={}", it) }

    private fun releaseExpired(workspaceId: WorkspaceId) {
        jdbc
            .sql("DELETE FROM wait_lease WHERE workspace_id = :workspaceId AND expires_at <= now()")
            .param("workspaceId", workspaceId.value)
            .update()
    }

    private fun heldSlots(workspaceId: WorkspaceId): Long =
        jdbc
            .sql("SELECT count(*) AS c FROM wait_lease WHERE workspace_id = :workspaceId")
            .param("workspaceId", workspaceId.value)
            .query { rs, _ -> rs.getLong("c") }
            .single()

    private inner class Lease(
        private val id: UUID,
    ) : WaitSlot {
        override fun close() {
            jdbc.sql("DELETE FROM wait_lease WHERE id = :id").param("id", id).update()
        }
    }

    private companion object {
        const val ACQUIRE_ATTEMPTS = 5
        const val MAX_ENUMERATED_SLOTS = 10_000L
        val log = LoggerFactory.getLogger(JdbcWaitSlots::class.java)
    }
}
