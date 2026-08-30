package email.testinbox.persistence

import email.testinbox.application.port.WaitSlot
import email.testinbox.application.port.WaitSlots
import email.testinbox.domain.WorkspaceId
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
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
        expiresAt: Instant,
    ): WaitSlot? {
        // Reclaim this workspace's dead slots first: without it a crashed node
        // would permanently shrink the tenant's allowance.
        releaseExpired(workspaceId, Instant.now())

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
                        SELECT :id, :workspaceId, candidate, now(), :expiresAt
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
                    .param("maxIndex", maxConcurrent - 1)
                    .param("expiresAt", Timestamps.toDb(expiresAt))
                    .update()
            if (inserted == 1) return Lease(id)
            // Zero rows means either every slot is taken, or a racer took the one
            // we chose. Distinguish by re-counting; only a full table is a refusal.
            if (heldSlots(workspaceId) >= maxConcurrent) return null
        }
        return null
    }

    override fun reapExpired(now: Instant): Int =
        jdbc
            .sql("DELETE FROM wait_lease WHERE expires_at <= :now")
            .param("now", Timestamps.toDb(now))
            .update()
            .also { if (it > 0) log.info("wait_lease_reaped count={}", it) }

    private fun releaseExpired(
        workspaceId: WorkspaceId,
        now: Instant,
    ) {
        jdbc
            .sql("DELETE FROM wait_lease WHERE workspace_id = :workspaceId AND expires_at <= :now")
            .param("workspaceId", workspaceId.value)
            .param("now", Timestamps.toDb(now))
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
        val log = LoggerFactory.getLogger(JdbcWaitSlots::class.java)
    }
}
