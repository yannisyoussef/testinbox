package email.testinbox.persistence

import email.testinbox.application.port.CorruptStorageStateException
import email.testinbox.application.port.InboxStorageFigures
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageRefusalSnapshot
import email.testinbox.application.port.StorageVisibility
import email.testinbox.application.port.WorkspaceStorageFigures
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageRefusalReason
import email.testinbox.domain.storage.StorageUsage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

/**
 * ADR-035 §13 authenticated visibility on PostgreSQL: the workspace's and a
 * set of inboxes' `base + Σdelta` and `Σ reservation`, plus each inbox's
 * refusal record, in ONE statement.
 *
 * - **One statement, one snapshot.** T2 moves bytes from a reservation into
 *   the ledger and the compactor moves them from a delta into a base row;
 *   each move is atomic, and one statement sees each wholly or not at all.
 *   The figures therefore always add up to the same bytes, folded or not
 *   (`StorageVisibilityTest` proves it against a concurrent compaction).
 * - **Missing rows read as zero.** A new inbox has no `inbox_storage` row
 *   until accounting or a refusal creates one, and a workspace created after
 *   the backfill has no account row until the compactor upserts it. Every
 *   join to a base row is a LEFT JOIN, and that scope's deltas and
 *   reservations are summed regardless.
 * - **Every reservation state counts**, however old (I5: stale is not free).
 * - **No lock.** Neither the admission nor the ledger advisory lock is taken;
 *   a visibility read never serializes admission.
 * - **Tenant-scoped in the SQL.** Every row is matched on the caller's
 *   workspace, so an inbox id of another tenant reads as empty rather than as
 *   that tenant's figures.
 *
 * It reads the same SQL shape as T1 (`JdbcStorageAdmission`), without the
 * global sums: the global figure is operator state and never leaves this
 * adapter's SELECT list, because it is not in it.
 */
@Repository
class JdbcStorageVisibility(
    private val jdbc: JdbcClient,
) : StorageVisibility {
    override fun read(
        workspaceId: WorkspaceId,
        inboxIds: Set<InboxId>,
    ): WorkspaceStorageFigures {
        val rows =
            jdbc
                .sql(
                    """
                    WITH ib AS (SELECT DISTINCT unnest(ARRAY[:inboxes]::uuid[]) AS id),
                         -- Each sum is aggregated once over the involved rows: the
                         -- cost must not grow with the inbox count times the backlog.
                         ws_delta AS (SELECT sum(bytes) AS bytes FROM storage_delta WHERE workspace_id = :workspace),
                         ws_reserved AS (SELECT sum(bytes) AS bytes FROM storage_reservation WHERE workspace_id = :workspace),
                         ib_delta AS (SELECT inbox_id AS id, sum(bytes) AS bytes FROM storage_delta
                                       WHERE workspace_id = :workspace AND inbox_id IN (SELECT id FROM ib) GROUP BY inbox_id),
                         ib_reserved AS (SELECT inbox_id AS id, sum(bytes) AS bytes FROM storage_reservation
                                          WHERE workspace_id = :workspace AND inbox_id IN (SELECT id FROM ib) GROUP BY inbox_id)
                    SELECT 'WORKSPACE' AS scope, NULL::uuid AS id,
                           coalesce(a.base_bytes, 0) + coalesce(d.bytes, 0) AS committed,
                           coalesce(r.bytes, 0) AS reserved,
                           0::bigint AS refusal_count, NULL::timestamptz AS last_refusal_at, NULL::text AS last_refusal_reason
                      FROM (SELECT 1) AS one
                      LEFT JOIN workspace_storage_account a ON a.workspace_id = :workspace
                      CROSS JOIN ws_delta d
                      CROSS JOIN ws_reserved r
                    UNION ALL
                    SELECT 'INBOX', ib.id,
                           coalesce(s.base_bytes, 0) + coalesce(d.bytes, 0),
                           coalesce(r.bytes, 0),
                           coalesce(s.refusal_count, 0), s.last_refusal_at, s.last_refusal_reason
                      FROM ib
                      LEFT JOIN inbox_storage s ON s.inbox_id = ib.id AND s.workspace_id = :workspace
                      LEFT JOIN ib_delta d ON d.id = ib.id
                      LEFT JOIN ib_reserved r ON r.id = ib.id
                    """.trimIndent(),
                ).param("workspace", workspaceId.value)
                .param("inboxes", inboxIds.map { it.value })
                .query { rs, _ -> Row(rs) }
                .list()
        val workspace = rows.single { it.scope == "WORKSPACE" }.usage
        val inboxes =
            rows
                .filter { it.scope == "INBOX" }
                .associate { InboxId(checkNotNull(it.id)) to InboxStorageFigures(it.usage, it.refusals()) }
        return WorkspaceStorageFigures(workspace, inboxes)
    }

    /** The refusal record alone, for a wait evaluation that has no need of the byte figures. */
    fun refusalsOf(
        workspaceId: WorkspaceId,
        inboxId: InboxId,
    ): StorageRefusalSnapshot =
        jdbc
            .sql(
                """
                SELECT refusal_count, last_refusal_at, last_refusal_reason FROM inbox_storage
                 WHERE inbox_id = :inbox AND workspace_id = :workspace
                """.trimIndent(),
            ).param("inbox", inboxId.value)
            .param("workspace", workspaceId.value)
            .query { rs, _ -> refusals(rs.getLong("refusal_count"), rs) }
            .optional()
            .orElse(StorageRefusalSnapshot.NONE)

    private class Row(
        rs: ResultSet,
    ) {
        val scope: String = rs.getString("scope")
        val id: UUID? = rs.getObject("id", UUID::class.java)
        val usage = StorageUsage(exactLong(rs.getBigDecimal("committed")), exactLong(rs.getBigDecimal("reserved")))
        private val refusalCount = rs.getLong("refusal_count")
        private val lastAt = Timestamps.fromDb(rs, "last_refusal_at")
        private val lastReason: String? = rs.getString("last_refusal_reason")

        fun refusals(): StorageRefusalSnapshot = refusals(refusalCount, lastAt, lastReason)
    }

    private companion object {
        fun refusals(
            count: Long,
            rs: ResultSet,
        ): StorageRefusalSnapshot = refusals(count, Timestamps.fromDb(rs, "last_refusal_at"), rs.getString("last_refusal_reason"))

        /**
         * A count of zero has no last refusal, whatever stray metadata a row
         * carries. A positive count must carry both: the §6a upsert writes
         * them together, so a row that disagrees is corrupt, and the read
         * fails closed rather than choosing a reason for the tenant.
         */
        fun refusals(
            count: Long,
            lastAt: java.time.Instant?,
            lastReason: String?,
        ): StorageRefusalSnapshot {
            if (count <= 0) {
                if (count < 0) throw CorruptStorageStateException("inbox_storage.refusal_count is negative")
                return StorageRefusalSnapshot.NONE
            }
            val reason =
                lastReason?.let { name -> StorageRefusalReason.entries.firstOrNull { it.name == name } }
                    ?: throw CorruptStorageStateException("inbox_storage records $count refusals but no known last_refusal_reason")
            val at = lastAt ?: throw CorruptStorageStateException("inbox_storage records $count refusals but no last_refusal_at")
            return StorageRefusalSnapshot(count, at, reason)
        }

        /** numeric → Long, exactly: a sum beyond 64 bits is corrupt accounting, never a quota figure. */
        fun exactLong(value: BigDecimal): Long =
            try {
                value.longValueExact()
            } catch (overflow: ArithmeticException) {
                throw StorageAccountingOverflowException("stored usage exceeds a signed 64-bit figure", overflow)
            }
    }
}
