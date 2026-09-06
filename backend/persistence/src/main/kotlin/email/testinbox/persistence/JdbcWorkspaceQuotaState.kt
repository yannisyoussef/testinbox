package email.testinbox.persistence

import email.testinbox.application.port.WorkspaceQuotaState
import email.testinbox.domain.WorkspaceId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Quota usage derived from the rows that actually exist (ADR-027 §5).
 *
 * Nothing here maintains a counter: ADR-009 hard-deletes through an
 * `ON DELETE CASCADE`, which runs no application code, so a counter could
 * never be decremented and would drift upward until every workspace wedged
 * at "quota exceeded". Derived usage cannot drift — it *is* the state.
 */
@Repository
class JdbcWorkspaceQuotaState(
    private val jdbc: JdbcClient,
) : WorkspaceQuotaState {
    /**
     * Advisory lock, taken as the first statement of the enclosing
     * transaction, so a derive-then-insert admission decision is atomic
     * (ADR-027 §6).
     *
     * It must precede any `inbox`/`message` write: `INSERT INTO message`
     * takes `FOR KEY SHARE` on the inbox row while the retention sweep's
     * `DELETE FROM inbox` wants an exclusive lock, so acquiring this guard
     * *after* those rows would close a lock-order cycle and deadlock.
     * An advisory-key collision between two workspaces costs a little extra
     * serialization and never correctness.
     */
    override fun guardAdmission(workspaceId: WorkspaceId) {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:key)")
            .param("key", workspaceId.value.mostSignificantBits xor workspaceId.value.leastSignificantBits)
            .query()
            .listOfRows()
    }

    override fun activeInboxCount(workspaceId: WorkspaceId): Long =
        jdbc
            .sql(
                """
                SELECT count(*) AS c FROM inbox
                 WHERE workspace_id = :workspaceId AND state IN ('ACTIVE', 'EXPIRING')
                """.trimIndent(),
            ).param("workspaceId", workspaceId.value)
            .query { rs, _ -> rs.getLong("c") }
            .single()

    /**
     * Raw MIME plus extracted attachment objects. Attachment bytes are counted
     * twice on purpose — once inside `raw.eml`, once as the extracted object —
     * because under ADR-005's per-message key layout both objects exist.
     */
    override fun storedBytes(workspaceId: WorkspaceId): Long =
        jdbc
            .sql(
                """
                SELECT COALESCE((SELECT sum(raw_size_bytes) FROM message WHERE workspace_id = :workspaceId), 0)
                     + COALESCE((SELECT sum(size_bytes) FROM attachment WHERE workspace_id = :workspaceId), 0)
                    AS total
                """.trimIndent(),
            ).param("workspaceId", workspaceId.value)
            .query { rs, _ -> rs.getLong("total") }
            .single()
}
