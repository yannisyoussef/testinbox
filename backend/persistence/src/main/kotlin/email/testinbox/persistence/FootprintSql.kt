package email.testinbox.persistence

import email.testinbox.application.port.ObservedFootprint
import java.sql.ResultSet

/**
 * The footprint inputs of T1's ONE statement (filesystem-containment contract
 * §2.4, §5.3; TI-STORAGE-006E PR D): *L* unclamped, the debt rows the newest
 * observation has not superseded (every pending row, and every row ordered at
 * or after its `started_seq`; all rows when there is none), the watermark, the
 * trust marker and the newest observation's validity fields. The same text is
 * used by [JdbcFootprintGate] for the check before recipient resolution, so
 * the two can never read different definitions.
 */
internal object FootprintSql {
    /**
     * The global figures, ONE scan of each table (V24, gate D step (1)): Σ
     * workspace bases from the definer-maintained `storage_global_base` row, and
     * Σ delta and Σ reservation over the in-flight tables. T1 reads them for its
     * GLOBAL row and, with the footprint, for *L*, from the same CTEs, so the two
     * can never disagree.
     *
     * A missing row (a restore: the row, like the base rows, is never backed up)
     * is never read as 0: the second branch then sums the base rows, exactly, in
     * the same snapshot. It is a one-time filter the planner skips while the row
     * exists, so the population is scanned only in that degenerate state, which
     * reconciliation reports as GLOBAL drift and the repair ends (V24); under
     * `ALL` the verifier also revokes trust while it lasts.
     */
    const val GLOBAL_CTES = """g_base AS (SELECT base_bytes AS bytes, base_objects AS objects FROM storage_global_base WHERE id = 1
                                    UNION ALL
                                    SELECT s.bytes, s.objects
                                      FROM (SELECT coalesce(sum(base_bytes), 0) AS bytes, coalesce(sum(base_objects), 0) AS objects
                                              FROM workspace_storage_account) s
                                     WHERE NOT EXISTS (SELECT 1 FROM storage_global_base WHERE id = 1)),
                         g_delta AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(objects), 0) AS objects FROM storage_delta),
                         g_reserved AS (SELECT coalesce(sum(bytes), 0) AS bytes, coalesce(sum(cardinality(object_keys)), 0) AS objects
                                          FROM storage_reservation)"""

    /**
     * The footprint-only CTEs, for a statement that already carries [GLOBAL_CTES] (T1).
     *
     * `fp_debt` is the ONE pass over the unsuperseded debt rows (gate D A3): the
     * total, and with `FILTER` the rows of no shared workspace (no workspace, or
     * one without a share), which [CohortSql]'s unattributed pool adds to. The
     * join is to the DISTINCT shared ids, so it never repeats a debt row.
     */
    val FOOTPRINT_CTES = """fp_newest AS (SELECT observed_at, started_seq, trash_bytes, block_size_bytes, capacity_bytes, written_by
                                       FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1),
                         ${CohortSql.SHARES},
                         fp_debt AS (SELECT coalesce(sum(d.bytes), 0) AS bytes, coalesce(sum(d.objects), 0) AS objects,
                                            coalesce(sum(d.bytes) FILTER (WHERE s.workspace_id IS NULL), 0) AS unattributed_bytes,
                                            coalesce(sum(d.objects) FILTER (WHERE s.workspace_id IS NULL), 0) AS unattributed_objects
                                       FROM storage_deletion_debt d
                                       LEFT JOIN (SELECT DISTINCT workspace_id FROM cp_shares) s ON s.workspace_id = d.workspace_id
                                      WHERE d.incurred_at = 'infinity'::timestamptz
                                         OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0)),
                         ${CohortSql.CTES}"""

    /** Every CTE [COLUMNS] needs, for a statement of its own (the footprint gate, rule P). */
    val CTES = "$GLOBAL_CTES,\n                         $FOOTPRINT_CTES"

    /** *L* from the [GLOBAL_CTES] sums: mode a's (and the footprint gate's) live set. */
    private const val LIVE_COLUMNS = """(SELECT bytes FROM g_base) + (SELECT bytes FROM g_delta) + (SELECT bytes FROM g_reserved) AS fp_live_bytes,
                           (SELECT objects FROM g_base) + (SELECT objects FROM g_delta) + (SELECT objects FROM g_reserved) AS fp_live_objects,
                           """

    /**
     * *L* = 0 for the share-partitioned T1 (PROPOSED, gate D W1): it reads no
     * global sum. Every live byte belongs to a share, and the shares fit the
     * declarations by the V25 bound the same statement checks, so rules (G) and
     * (C) hold by that partition, not by this figure (partitioned-t1-correctness.md).
     */
    private const val NO_LIVE_COLUMNS = """0::numeric AS fp_live_bytes, 0::numeric AS fp_live_objects,
                           """

    /** Debt, trust, watermark, observation and cohort pool: identical in both. */
    private val REST_COLUMNS = """(SELECT bytes FROM fp_debt) AS fp_debt_bytes,
                           (SELECT objects FROM fp_debt) AS fp_debt_objects,
                           coalesce((SELECT trusted_epoch IS NOT DISTINCT FROM distrust_epoch FROM storage_footprint_trust WHERE id = 1), false)
                             AS fp_trusted,
                           coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1), 0) AS fp_watermark,
                           coalesce((SELECT distrusted_seq FROM storage_footprint_trust WHERE id = 1), 0) AS fp_distrusted_seq,
                           (SELECT started_seq FROM fp_newest) AS fp_started_seq,
                           (SELECT trash_bytes FROM fp_newest) AS fp_trash_bytes,
                           (SELECT block_size_bytes FROM fp_newest) AS fp_block_size,
                           (SELECT capacity_bytes FROM fp_newest) AS fp_capacity,
                           (SELECT written_by::text FROM fp_newest) AS fp_written_by, ${CohortSql.COLUMN}"""

    val COLUMNS = LIVE_COLUMNS + REST_COLUMNS

    /** [COLUMNS] without the global live sums, for a statement that carries no [GLOBAL_CTES]. */
    val PARTITIONED_COLUMNS = NO_LIVE_COLUMNS + REST_COLUMNS

    /** The same columns, typed, for the rows that do not carry them. */
    const val NONE = """NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric, NULL::boolean, NULL::bigint,
                           NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text, NULL::jsonb"""

    /** The figures of a row carrying [COLUMNS]; null when a sum does not fit a signed 64-bit figure (corrupt totals). */
    fun read(rs: ResultSet): ObservedFootprint? {
        fun exact(column: String): Long? = rs.getBigDecimal(column)?.let { runCatching { it.longValueExact() }.getOrNull() }

        fun nullableLong(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }

        return ObservedFootprint(
            liveBytes = exact("fp_live_bytes") ?: return null,
            liveObjects = exact("fp_live_objects") ?: return null,
            debtBytes = exact("fp_debt_bytes") ?: return null,
            debtObjects = exact("fp_debt_objects") ?: return null,
            countsTrusted = rs.getBoolean("fp_trusted"),
            compactedThroughSeq = rs.getLong("fp_watermark"),
            startedSeq = nullableLong("fp_started_seq"),
            trashBytes = nullableLong("fp_trash_bytes"),
            blockSizeBytes = nullableLong("fp_block_size"),
            capacityBytes = nullableLong("fp_capacity"),
            writtenBy = rs.getString("fp_written_by"),
            distrustedSeq = rs.getLong("fp_distrusted_seq"),
            cohort = CohortSql.read(rs),
        )
    }
}
