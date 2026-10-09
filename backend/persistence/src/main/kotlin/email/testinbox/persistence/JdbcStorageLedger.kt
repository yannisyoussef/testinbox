package email.testinbox.persistence

import email.testinbox.application.port.AccountingDrift
import email.testinbox.application.port.AccountingScope
import email.testinbox.application.port.DeletionDebtState
import email.testinbox.application.port.FilesystemObservation
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
        // V10 (owner review b, §2): the fold runs inside storage_compact_ledger(), a
        // SECURITY DEFINER function holding exactly the statement this method ran:
        // the ledger try-lock, the objects folded here (so V8's folding trigger stays
        // out), and one DELETE ... RETURNING feeding both upserts in key order. The
        // application role therefore holds no write on storage_delta or the base
        // rows, and cannot under-count the footprint a trust mark vouches for.
        val folded =
            jdbc
                .sql("SELECT storage_compact_ledger(:batch)")
                .param("batch", batch)
                .query(Int::class.java)
                .single()
        return if (folded < 0) LedgerCompaction(lockAcquired = false, foldedRows = 0) else LedgerCompaction(true, folded)
    }

    override fun state(): LedgerState =
        jdbc
            .sql(
                """
                SELECT (SELECT count(*) FROM storage_delta) AS unfolded,
                       (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account)
                     + (SELECT coalesce(sum(bytes), 0) FROM storage_delta) AS committed,
                       (SELECT coalesce(sum(base_objects), 0) FROM workspace_storage_account)
                     + (SELECT coalesce(sum(objects), 0) FROM storage_delta) AS objects,
                       (SELECT coalesce(sum(bytes), 0) FROM storage_reservation) AS reserved,
                       (SELECT coalesce(sum(cardinality(object_keys)), 0) FROM storage_reservation) AS reserved_objects
                """.trimIndent(),
            ).query { rs, _ ->
                LedgerState(
                    unfoldedRows = rs.getLong("unfolded"),
                    committedBytes = rs.getLong("committed"),
                    committedObjects = rs.getLong("objects"),
                    reservedBytes = rs.getLong("reserved"),
                    reservedObjects = rs.getLong("reserved_objects"),
                )
            }.single()

    /**
     * ONE statement (contract §5.3): the newest observation by `started_seq`,
     * the debt rows ordered at or after it, every PENDING row whatever its
     * sequence, the compaction watermark and the trust marker, so a
     * compaction, a resolution or a new observation is seen wholly before or
     * wholly after. With no observation, every row counts.
     */
    override fun deletionDebt(): DeletionDebtState =
        jdbc
            .sql(
                """
                WITH newest AS (
                    SELECT * FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1
                ),
                debt AS (
                    SELECT d.bytes, d.objects, d.incurred_at = 'infinity'::timestamptz AS pending
                      FROM storage_deletion_debt d
                     WHERE d.incurred_at = 'infinity'::timestamptz
                        OR d.seq >= coalesce((SELECT started_seq FROM newest), 0)
                )
                SELECT (SELECT coalesce(sum(bytes), 0) FROM debt) AS bytes,
                       (SELECT coalesce(sum(objects), 0) FROM debt) AS objects,
                       (SELECT coalesce(sum(bytes), 0) FROM debt WHERE pending) AS pending_bytes,
                       (SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1) AS watermark,
                       (SELECT trusted_epoch IS NOT DISTINCT FROM distrust_epoch FROM storage_footprint_trust WHERE id = 1)
                           AS trusted,
                       n.started_seq, n.written_by,
                       n.started_at, n.observed_at, n.source, n.block_size_bytes, n.capacity_bytes, n.used_bytes, n.avail_bytes,
                       n.inodes_total, n.inodes_used, n.trash_bytes, n.minio_sys_bytes
                  FROM (SELECT 1) AS one
                  LEFT JOIN newest n ON true
                """.trimIndent(),
            ).query { rs, _ ->
                DeletionDebtState(
                    unsupersededBytes = rs.getLong("bytes"),
                    unsupersededObjects = rs.getLong("objects"),
                    pendingBytes = rs.getLong("pending_bytes"),
                    compactedThroughSeq = rs.getLong("watermark"),
                    // A missing trust row reads as untrusted.
                    countsTrusted = rs.getBoolean("trusted"),
                    observation =
                        Timestamps.fromDb(rs, "started_at")?.let { startedAt ->
                            FilesystemObservation(
                                startedSeq = rs.getLong("started_seq"),
                                writtenBy = rs.getString("written_by"),
                                startedAt = startedAt,
                                observedAt = checkNotNull(Timestamps.fromDb(rs, "observed_at")),
                                source = rs.getString("source"),
                                blockSizeBytes = rs.getLong("block_size_bytes"),
                                capacityBytes = rs.getLong("capacity_bytes"),
                                usedBytes = rs.getLong("used_bytes"),
                                availBytes = rs.getLong("avail_bytes"),
                                inodesTotal = rs.getLong("inodes_total"),
                                inodesUsed = rs.getLong("inodes_used"),
                                trashBytes = rs.getLong("trash_bytes"),
                                minioSysBytes = rs.getLong("minio_sys_bytes"),
                            )
                        },
                )
            }.single()

    /**
     * One statement: V8's `storage_compact_deletion_debt()` deletes the rows
     * ordered before the newest observation's start, which are inside its
     * `trash_bytes` already, never a pending one, and raises the watermark in
     * the same transaction.
     */
    override fun compactDeletionDebt(): Int =
        jdbc
            .sql("SELECT storage_compact_deletion_debt()")
            .query(Int::class.java)
            .single()

    override fun confirmTrust(): Boolean = checkNotNull(transactions.execute { confirmTrustInTransaction() })

    /**
     * Contract §4.5: the ledger lock FIRST, so no compaction or repair moves
     * a figure between the check and the mark; then the epoch is read, the
     * WORKSPACE counts (whose sum is the global potential) are proven clean
     * against the rows, and the epoch read is marked trusted compare-and-set,
     * so a folding or a drift that bumped the epoch meanwhile is never
     * overwritten. Returns whether the counts are trusted.
     */
    private fun confirmTrustInTransaction(): Boolean {
        readCommitted()
        // V10 (owner review b, §2): the database verifies and marks. The function
        // takes the ledger lock, recreates a row lost to a restore, checks every
        // workspace's bytes and objects against the rows, and marks compare-and-set.
        // The application holds EXECUTE on it and no write on the trusted columns.
        return jdbc
            .sql("SELECT storage_confirm_footprint_trust()")
            .query(Boolean::class.java)
            .single()
    }

    override fun findDrift(): List<AccountingDrift> =
        jdbc
            .sql("$DRIFT SELECT scope, id, derived, accounted, derived_objects, accounted_objects FROM drift")
            .query { rs, _ -> drift(rs) }
            .list()

    override fun repairDrift(): List<AccountingDrift> = checkNotNull(transactions.execute { repairInTransaction() })

    private fun repairInTransaction(): List<AccountingDrift> {
        readCommitted()
        // V10: storage_repair_ledger() holds the statement this method ran. It takes
        // the blocking ledger lock, and in ONE statement derives, writes
        // base := derived − Σdelta for every drifted workspace and inbox, and
        // revokes trust on workspace drift (contract §4.5).
        return jdbc
            .sql("SELECT scope, id, derived, accounted, derived_objects, accounted_objects FROM storage_repair_ledger()")
            .query { rs, _ -> drift(rs) }
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

    private fun drift(rs: ResultSet): AccountingDrift =
        AccountingDrift(
            scope = AccountingScope.valueOf(rs.getString("scope")),
            id = rs.getObject("id", UUID::class.java),
            derivedBytes = rs.getLong("derived"),
            accountedBytes = rs.getLong("accounted"),
            derivedObjects = rs.getLong("derived_objects"),
            accountedObjects = rs.getLong("accounted_objects"),
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
         * and every live inbox, in bytes AND in objects (TI-STORAGE-006E: one
         * object per message row and per attachment row). A scope drifts when
         * either figure disagrees. Figures that match are dropped. An inbox's
         * attachments are found through their message, as in V6's recompute.
         */
        private val DRIFT =
            """
            WITH derived_workspace AS (
                SELECT workspace_id, sum(bytes) AS bytes, count(*) AS objects
                  FROM (SELECT workspace_id, raw_size_bytes AS bytes FROM message
                        UNION ALL
                        SELECT workspace_id, size_bytes FROM attachment) s
                 GROUP BY workspace_id
            ),
            unfolded_workspace AS (
                SELECT workspace_id, sum(bytes) AS bytes, sum(objects) AS objects FROM storage_delta GROUP BY workspace_id
            ),
            derived_inbox AS (
                SELECT inbox_id, sum(bytes) AS bytes, count(*) AS objects
                  FROM (SELECT inbox_id, raw_size_bytes AS bytes FROM message
                        UNION ALL
                        SELECT m.inbox_id, a.size_bytes FROM attachment a JOIN message m ON m.id = a.message_id) s
                 GROUP BY inbox_id
            ),
            unfolded_inbox AS (
                SELECT inbox_id, sum(bytes) AS bytes, sum(objects) AS objects
                  FROM storage_delta WHERE inbox_id IS NOT NULL GROUP BY inbox_id
            ),
            drift AS (
                SELECT 'WORKSPACE' AS scope, w.id,
                       coalesce(d.bytes, 0) AS derived,
                       coalesce(u.bytes, 0) AS unfolded,
                       coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0) AS accounted,
                       coalesce(d.objects, 0) AS derived_objects,
                       coalesce(u.objects, 0) AS unfolded_objects,
                       coalesce(a.base_objects, 0) + coalesce(u.objects, 0) AS accounted_objects
                  FROM workspace w
                  LEFT JOIN derived_workspace d ON d.workspace_id = w.id
                  LEFT JOIN unfolded_workspace u ON u.workspace_id = w.id
                  LEFT JOIN workspace_storage_account a ON a.workspace_id = w.id
                 WHERE coalesce(d.bytes, 0) <> coalesce(a.base_bytes, 0) + coalesce(u.bytes, 0)
                    OR coalesce(d.objects, 0) <> coalesce(a.base_objects, 0) + coalesce(u.objects, 0)
                UNION ALL
                SELECT 'INBOX', i.id,
                       coalesce(d.bytes, 0),
                       coalesce(u.bytes, 0),
                       coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0),
                       coalesce(d.objects, 0),
                       coalesce(u.objects, 0),
                       coalesce(s.base_objects, 0) + coalesce(u.objects, 0)
                  FROM inbox i
                  LEFT JOIN derived_inbox d ON d.inbox_id = i.id
                  LEFT JOIN unfolded_inbox u ON u.inbox_id = i.id
                  LEFT JOIN inbox_storage s ON s.inbox_id = i.id
                 WHERE coalesce(d.bytes, 0) <> coalesce(s.base_bytes, 0) + coalesce(u.bytes, 0)
                    OR coalesce(d.objects, 0) <> coalesce(s.base_objects, 0) + coalesce(u.objects, 0)
            )
            """.trimIndent()
    }
}
