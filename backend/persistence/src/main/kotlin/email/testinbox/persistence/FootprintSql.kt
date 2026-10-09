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
    const val CTES = """fp_newest AS (SELECT started_seq, trash_bytes, block_size_bytes, capacity_bytes, written_by
                                       FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1),
                         fp_debt AS (SELECT coalesce(sum(d.bytes), 0) AS bytes, coalesce(sum(d.objects), 0) AS objects
                                       FROM storage_deletion_debt d
                                      WHERE d.incurred_at = 'infinity'::timestamptz
                                         OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0))"""

    const val COLUMNS = """(SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account)
                         + (SELECT coalesce(sum(bytes), 0) FROM storage_delta)
                         + (SELECT coalesce(sum(bytes), 0) FROM storage_reservation) AS fp_live_bytes,
                           (SELECT coalesce(sum(base_objects), 0) FROM workspace_storage_account)
                         + (SELECT coalesce(sum(objects), 0) FROM storage_delta)
                         + (SELECT coalesce(sum(cardinality(object_keys)), 0) FROM storage_reservation) AS fp_live_objects,
                           (SELECT bytes FROM fp_debt) AS fp_debt_bytes,
                           (SELECT objects FROM fp_debt) AS fp_debt_objects,
                           coalesce((SELECT trusted_epoch IS NOT DISTINCT FROM distrust_epoch FROM storage_footprint_trust WHERE id = 1), false)
                             AS fp_trusted,
                           coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1), 0) AS fp_watermark,
                           coalesce((SELECT distrusted_seq FROM storage_footprint_trust WHERE id = 1), 0) AS fp_distrusted_seq,
                           (SELECT started_seq FROM fp_newest) AS fp_started_seq,
                           (SELECT trash_bytes FROM fp_newest) AS fp_trash_bytes,
                           (SELECT block_size_bytes FROM fp_newest) AS fp_block_size,
                           (SELECT capacity_bytes FROM fp_newest) AS fp_capacity,
                           (SELECT written_by::text FROM fp_newest) AS fp_written_by"""

    /** The same columns, typed, for the rows that do not carry them. */
    const val NONE = """NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric, NULL::boolean, NULL::bigint,
                           NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint, NULL::text"""

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
        )
    }
}
