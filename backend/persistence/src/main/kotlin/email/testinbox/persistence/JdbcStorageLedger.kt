package email.testinbox.persistence

import email.testinbox.application.port.AccountingDrift
import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.LedgerCompaction
import email.testinbox.application.port.LedgerState
import email.testinbox.application.port.StorageLedger
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionOperations
import java.sql.ResultSet
import java.util.UUID

/**
 * PostgreSQL side of the ADR-035 §10 ledger (V6).
 *
 * The triggers append `storage_delta`, and this adapter is the only code that
 * writes the base figures. Every method is a single statement, or a single
 * statement behind an advisory lock. A single statement is what makes each
 * figure it reads or writes consistent: under READ COMMITTED each statement
 * takes its own snapshot, so a compaction or a message commit is seen either
 * whole or not at all.
 *
 * Lock order: the ledger's advisory lock, then `storage_delta`,
 * `workspace_storage_account` and `inbox_storage` rows, then FOR KEY SHARE on
 * `workspace`/`inbox` through the foreign keys. The retention delete takes the
 * inbox before its `inbox_storage` row, and never waits on anything this
 * adapter holds, so the two cannot form a cycle.
 */
@Repository
class JdbcStorageLedger(
    private val jdbc: JdbcClient,
    /**
     * Explicit rather than `@Transactional`. The advisory lock is
     * transaction-scoped, so it must share one transaction with the statement
     * it guards, and that has to hold on every construction path, not only
     * behind a Spring proxy. The accounting tests build this adapter over
     * isolated databases, and a proxy-only guarantee would leave them proving
     * nothing about the lock.
     */
    private val transactions: TransactionOperations,
) : StorageLedger {
    override fun compact(batch: Int): LedgerCompaction {
        require(batch > 0) { "batch must be positive" }
        return checkNotNull(transactions.execute { compactInTransaction(batch) })
    }

    private fun compactInTransaction(batch: Int): LedgerCompaction {
        readCommitted()
        if (!tryLedgerLock()) return LedgerCompaction(lockAcquired = false, foldedRows = 0)

        // One statement. The DELETE's RETURNING feeds both upserts, so a
        // delta leaves the ledger in the same instant its bytes reach a base.
        // Only committed rows are visible, so an uncommitted delta, or a gap in
        // the id sequence, just waits for the next pass. An inbox that no
        // longer exists keeps only its workspace share. A delta naming a
        // workspace that does not exist (only corrupt data can produce one,
        // since attachment.workspace_id has no FK) is dropped rather than
        // failing every pass and wedging compaction for good; reconciliation
        // derives from the rows, so nothing it guards is lost. Rows are
        // upserted in key order, so a later writer that locks base rows in
        // ascending order (the ADR-035 §6 refusal upsert) cannot form a cycle
        // with a compaction. If a concurrent inbox delete breaks a foreign key
        // between the EXISTS and the insert, the statement fails, the
        // transaction rolls back with every delta intact, and the next pass
        // retries.
        val folded =
            jdbc
                .sql(
                    """
                    WITH folded AS (
                        DELETE FROM storage_delta
                         WHERE id IN (SELECT id FROM storage_delta ORDER BY id LIMIT :batch)
                        RETURNING workspace_id, inbox_id, bytes
                    ),
                    workspaces AS (
                        INSERT INTO workspace_storage_account (workspace_id, base_bytes)
                        SELECT f.workspace_id, sum(f.bytes)
                          FROM folded f
                         WHERE EXISTS (SELECT 1 FROM workspace w WHERE w.id = f.workspace_id)
                         GROUP BY f.workspace_id
                         ORDER BY f.workspace_id
                        ON CONFLICT (workspace_id)
                            DO UPDATE SET base_bytes = workspace_storage_account.base_bytes + EXCLUDED.base_bytes
                        RETURNING 1
                    ),
                    inboxes AS (
                        INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
                        SELECT f.inbox_id, (array_agg(f.workspace_id))[1], sum(f.bytes)
                          FROM folded f
                         WHERE f.inbox_id IS NOT NULL
                           AND EXISTS (SELECT 1 FROM inbox i WHERE i.id = f.inbox_id)
                         GROUP BY f.inbox_id
                         ORDER BY f.inbox_id
                        ON CONFLICT (inbox_id)
                            DO UPDATE SET base_bytes = inbox_storage.base_bytes + EXCLUDED.base_bytes
                        RETURNING 1
                    )
                    SELECT (SELECT count(*) FROM folded) AS folded,
                           (SELECT count(*) FROM workspaces) AS workspaces,
                           (SELECT count(*) FROM inboxes) AS inboxes
                    """.trimIndent(),
                ).param("batch", batch)
                .query { rs, _ -> rs.getInt("folded") }
                .single()
        return LedgerCompaction(lockAcquired = true, foldedRows = folded)
    }

    override fun state(): LedgerState =
        jdbc
            .sql(
                """
                SELECT (SELECT count(*) FROM storage_delta) AS unfolded,
                       (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account)
                     + (SELECT coalesce(sum(bytes), 0) FROM storage_delta) AS committed
                """.trimIndent(),
            ).query { rs, _ -> LedgerState(rs.getLong("unfolded"), rs.getLong("committed")) }
            .single()

    override fun findDrift(): List<AccountingDrift> =
        jdbc
            .sql("$DRIFT SELECT scope, id, derived, accounted FROM drift")
            .query { rs, _ -> drift(rs) }
            .list()

    override fun repairDrift(): List<AccountingDrift> = checkNotNull(transactions.execute { repairInTransaction() })

    private fun repairInTransaction(): List<AccountingDrift> {
        readCommitted()
        // Blocking, unlike the compactor's try-lock: a repair is rare, and it
        // must not interleave with a compaction moving the same bytes.
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:class, :ledger)")
            .param("class", STORAGE_LOCK_CLASS)
            .param("ledger", LEDGER_LOCK)
            .query()
            .listOfRows()

        // One statement: the derivation, the deltas and the bases are all read
        // in the snapshot the corrections are written from. base := derived −
        // Σdelta is therefore exact at that snapshot. A write that commits
        // afterwards appends its own delta and never touches a base, so
        // base + Σdelta stays equal to the derivation.
        return jdbc
            .sql(
                """
                $DRIFT,
                workspace_fix AS (
                    INSERT INTO workspace_storage_account (workspace_id, base_bytes, reconciled_at)
                    SELECT id, derived - unfolded, now() FROM drift WHERE scope = 'WORKSPACE' ORDER BY id
                    ON CONFLICT (workspace_id)
                        DO UPDATE SET base_bytes = EXCLUDED.base_bytes, reconciled_at = EXCLUDED.reconciled_at
                    RETURNING 1
                ),
                inbox_fix AS (
                    INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
                    SELECT d.id, i.workspace_id, d.derived - d.unfolded
                      FROM drift d JOIN inbox i ON i.id = d.id
                     WHERE d.scope = 'INBOX'
                     ORDER BY d.id
                    ON CONFLICT (inbox_id) DO UPDATE SET base_bytes = EXCLUDED.base_bytes
                    RETURNING 1
                )
                SELECT scope, id, derived, accounted,
                       (SELECT count(*) FROM workspace_fix) + (SELECT count(*) FROM inbox_fix) AS fixed
                  FROM drift
                """.trimIndent(),
            ).query { rs, _ -> drift(rs) }
            .list()
    }

    /**
     * Stated, not inherited from the server default (as `SpringTransactionRunner`
     * does for ADR-033). The repair relies on its snapshot being taken *after*
     * the ledger lock is granted, and that is true only under READ COMMITTED.
     * Under REPEATABLE READ, a compaction committing while the repair waited
     * would turn into a serialization failure, and a spurious `failed`
     * reconciliation. It must be the transaction's first statement.
     */
    private fun readCommitted() {
        jdbc.sql("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").update()
    }

    private fun tryLedgerLock(): Boolean =
        jdbc
            .sql("SELECT pg_try_advisory_xact_lock(:class, :ledger)")
            .param("class", STORAGE_LOCK_CLASS)
            .param("ledger", LEDGER_LOCK)
            .query(Boolean::class.java)
            .single()

    private fun drift(rs: ResultSet): AccountingDrift =
        AccountingDrift(
            scope = AccountingScope.valueOf(rs.getString("scope")),
            id = rs.getObject("id", UUID::class.java),
            derivedBytes = rs.getLong("derived"),
            accountedBytes = rs.getLong("accounted"),
        )

    companion object {
        /**
         * ADR-035 advisory locks use the two-`int4` key space, which PostgreSQL
         * keeps disjoint from the one-`bigint` space of the ADR-027 §6
         * workspace guard. Class 35 is ADR-035. Id 1 is reserved for global
         * admission (a later slice), and id 2 is the ledger, also taken by V6's
         * `storage_account_recompute()`.
         */
        const val STORAGE_LOCK_CLASS = 35
        const val LEDGER_LOCK = 2

        /**
         * The ADR-027 §5 derivation (raw bytes plus every attachment, so an
         * attachment counts twice) against `base + Σdelta`, for every workspace
         * and every live inbox. Figures that match are dropped. An inbox's
         * attachments are found through their message, as in V6's recompute.
         */
        private val DRIFT =
            """
            WITH derived_workspace AS (
                SELECT workspace_id, sum(bytes) AS bytes
                  FROM (SELECT workspace_id, raw_size_bytes AS bytes FROM message
                        UNION ALL
                        SELECT workspace_id, size_bytes FROM attachment) s
                 GROUP BY workspace_id
            ),
            unfolded_workspace AS (
                SELECT workspace_id, sum(bytes) AS bytes FROM storage_delta GROUP BY workspace_id
            ),
            derived_inbox AS (
                SELECT inbox_id, sum(bytes) AS bytes
                  FROM (SELECT inbox_id, raw_size_bytes AS bytes FROM message
                        UNION ALL
                        SELECT m.inbox_id, a.size_bytes FROM attachment a JOIN message m ON m.id = a.message_id) s
                 GROUP BY inbox_id
            ),
            unfolded_inbox AS (
                SELECT inbox_id, sum(bytes) AS bytes FROM storage_delta WHERE inbox_id IS NOT NULL GROUP BY inbox_id
            ),
            drift AS (
                SELECT 'WORKSPACE' AS scope, w.id,
                       coalesce(d.bytes, 0) AS derived,
                       coalesce(u.bytes, 0) AS unfolded,
                       coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0) AS accounted
                  FROM workspace w
                  LEFT JOIN derived_workspace d ON d.workspace_id = w.id
                  LEFT JOIN unfolded_workspace u ON u.workspace_id = w.id
                  LEFT JOIN workspace_storage_account a ON a.workspace_id = w.id
                 WHERE coalesce(d.bytes, 0) <> coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0)
                UNION ALL
                SELECT 'INBOX', i.id,
                       coalesce(d.bytes, 0),
                       coalesce(u.bytes, 0),
                       coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0)
                  FROM inbox i
                  LEFT JOIN derived_inbox d ON d.inbox_id = i.id
                  LEFT JOIN unfolded_inbox u ON u.inbox_id = i.id
                  LEFT JOIN inbox_storage s ON s.inbox_id = i.id
                 WHERE coalesce(d.bytes, 0) <> coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0)
            )
            """.trimIndent()
    }
}
