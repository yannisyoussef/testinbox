package email.testinbox.benchmark

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.ObservedFootprint
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.WorkspaceStorageBudget
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.FootprintAdmission
import email.testinbox.domain.storage.StorageUsage
import email.testinbox.persistence.JdbcStorageAdmission
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * **BENCHMARK-ONLY measurement variant, never a production mode.** The W1
 * `COHORT_PARTITIONED` T1 (#124, W1 design note "Share-partitioned design"),
 * built so ONE gate run can measure CHOSEN, NO_LOCK and PARTITIONED on the same
 * host. Whether such a mode exists is an owner decision and an ADR amendment;
 * nothing in a deployable constructs or references this class, and its results
 * are reported apart from gate D and never count as its evidence.
 *
 * What it does, over the real adapter's transaction (`admit` is production's,
 * final; only the two protected steps are overridden, like the NO_LOCK mutant):
 * - **Locks.** `(35, 1)` in SHARED mode, then one exclusive
 *   `(38, key(workspace))` per distinct workspace of the event, in ascending
 *   key order (a key collision only adds serialization). Every other `(35, 1)`
 *   holder (rule P, lifecycle, the node fence, reserve/revoke, beta admission)
 *   stays exclusive in production code, so it still excludes every T1.
 * - **One statement, no population sums.** It REUSES production's statement
 *   text: the per-workspace and per-inbox CTEs and rows are cut from
 *   `JdbcStorageAdmission.snapshotStatement` verbatim. Only the GLOBAL row is
 *   replaced: no Σ base, Σ delta or Σ reservation (the unreferenced `g_*` CTEs
 *   are never executed), and in their place the cohort-scaled readiness facts
 *   W1 keeps: the newest observation, trust, watermark, the unattributed debt
 *   `D_u`, the approved plan, the node fence and "a workspace with usage lacks
 *   a share" (stopping at the first).
 * - **The decision** is production's `StorageAdmission` under `ALL` with the
 *   footprint policy: the per-workspace payload and footprint shares refuse,
 *   and rules (G)/(C) run on `L = 0, D = D_u, W` (in the partitioned mode they
 *   hold by the static partition Π instead).
 *
 * Measurement limits, stated in the evidence: the Π approval verdict (pure
 * CPU, a few hashes) is not evaluated, because the gate population has no
 * approved plan and 10 000 unshared workspaces holding usage; the readiness
 * facts are read for their cost only. In that population "a workspace with
 * usage lacks a share" stops at its first row; in a real partitioned
 * deployment, where only the cohort holds usage, that check needs an index or
 * a database-maintained count to stay cohort-scaled.
 */
class PartitionedAdmissionStore(
    private val jdbc: JdbcClient,
    transactions: TransactionTemplate,
) : JdbcStorageAdmission(jdbc, transactions, readsFootprint = true) {
    /** The variant's statement: production's per-workspace and per-inbox text, a cohort-scaled GLOBAL row. */
    val partitionedStatement: String = compose(JdbcStorageAdmission(jdbc, transactions).snapshotStatement)

    override fun acquireAdmissionLock() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock_shared(:class, :admission)")
            .param("class", STORAGE_LOCK_CLASS)
            .param("admission", ADMISSION_LOCK)
            .query()
            .singleRow()
    }

    override fun readSnapshot(scope: StorageAdmissionScope): StorageUsageSnapshot {
        val keys = workspaceLockKeys(scope.workspaceIds.map { it.value })
        jdbc
            .sql("SELECT count(pg_advisory_xact_lock(:class, k)) FROM unnest(CAST(:keys AS int[])) WITH ORDINALITY AS t(k, n)")
            .param("class", WORKSPACE_LOCK_CLASS)
            .param("keys", array("int4", keys.toTypedArray()), Types.ARRAY)
            .query(Long::class.java)
            .single()
        val rows =
            jdbc
                .sql(partitionedStatement)
                .param("workspaces", array("uuid", scope.workspaceIds.map { it.value }.toTypedArray()), Types.ARRAY)
                .param("inboxes", array("uuid", scope.inboxIds.map { it.value }.toTypedArray()), Types.ARRAY)
                .query { rs, _ -> row(rs) }
                .list()
        val global = rows.single { it.scope == "GLOBAL" }
        val workspaces = rows.filter { it.scope == "WORKSPACE" }
        return StorageUsageSnapshot(
            t0 = global.t0,
            global = StorageUsage(0, 0, 0, 0),
            workspaces = workspaces.associate { WorkspaceId(checkNotNull(it.id)) to it.usage },
            inboxes =
                rows
                    .filter { it.scope == "INBOX" }
                    .associate { InboxId(checkNotNull(it.id)) to InboxStorageUsage(it.owner?.let(::WorkspaceId), it.usage) },
            footprint = global.footprint,
            budgets = workspaces.filter { it.budget != null }.associate { WorkspaceId(checkNotNull(it.id)) to checkNotNull(it.budget) },
            inactiveWorkspaces = workspaces.filter { it.inactive }.mapTo(HashSet()) { WorkspaceId(checkNotNull(it.id)) },
        )
    }

    private class Row(
        val scope: String,
        val id: UUID?,
        val owner: UUID?,
        val t0: java.time.Instant,
        val usage: StorageUsage,
        val budget: WorkspaceStorageBudget?,
        val inactive: Boolean,
        val footprint: ObservedFootprint?,
    )

    private fun row(rs: ResultSet): Row {
        fun long(column: String): Long = rs.getBigDecimal(column)?.longValueExact() ?: 0L

        fun nullableLong(column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
        val scope = rs.getString("scope")
        return Row(
            scope = scope,
            id = rs.getObject("id", UUID::class.java),
            owner = rs.getObject("owner", UUID::class.java),
            t0 = rs.getTimestamp("t0").toInstant(),
            usage =
                StorageUsage(
                    long("committed"),
                    long("reserved"),
                    maxOf(0, long("committed_objects")),
                    maxOf(0, long("reserved_objects")),
                ),
            budget =
                rs.getObject("payload_limit")?.let {
                    WorkspaceStorageBudget(
                        rs.getLong("payload_limit"),
                        rs.getLong("footprint_limit"),
                        FootprintAdmission.Load(long("cleanup_bytes"), long("cleanup_objects")),
                    )
                },
            inactive = rs.getBoolean("inactive"),
            footprint =
                if (scope != "GLOBAL") {
                    null
                } else {
                    ObservedFootprint(
                        liveBytes = 0,
                        liveObjects = 0,
                        debtBytes = long("fp_debt_bytes"),
                        debtObjects = long("fp_debt_objects"),
                        countsTrusted = rs.getBoolean("fp_trusted"),
                        compactedThroughSeq = rs.getLong("fp_watermark"),
                        startedSeq = nullableLong("fp_started_seq"),
                        trashBytes = nullableLong("fp_trash_bytes"),
                        blockSizeBytes = nullableLong("fp_block_size"),
                        capacityBytes = nullableLong("fp_capacity"),
                        writtenBy = rs.getString("fp_written_by"),
                        distrustedSeq = rs.getLong("fp_distrusted_seq"),
                        // Read for its cost (fp_cohort), not decided: see the class comment.
                        cohort = null,
                    )
                },
        )
    }

    private fun array(
        type: String,
        values: Array<*>,
    ): AbstractSqlTypeValue =
        object : AbstractSqlTypeValue() {
            override fun createTypeValue(
                connection: Connection,
                sqlType: Int,
                typeName: String?,
            ): Any = connection.createArrayOf(type, values)
        }

    companion object {
        /** W1's per-workspace lock class. Unused by every production lock (ADR-035 uses class 35). */
        const val WORKSPACE_LOCK_CLASS = 38

        /** Distinct, ascending: the total order that makes the per-workspace locks deadlock-free. */
        fun workspaceLockKeys(workspaces: Collection<UUID>): List<Int> = workspaces.map { it.hashCode() }.distinct().sorted()

        private const val GLOBAL_ROW_MARKER = "SELECT 'GLOBAL'"
        private const val WORKSPACE_ROW_MARKER = "SELECT 'WORKSPACE'"

        /**
         * Production's statement with its GLOBAL row replaced. The CTE prefix and
         * the WORKSPACE and INBOX rows are production's text, byte for byte.
         */
        fun compose(production: String): String {
            require(production.contains(GLOBAL_ROW_MARKER) && production.contains(WORKSPACE_ROW_MARKER)) {
                "production's T1 statement changed shape; the partitioned variant must be re-derived"
            }
            val prefix = production.substringBefore(GLOBAL_ROW_MARKER)
            val rows = production.substringAfter(WORKSPACE_ROW_MARKER)
            return "$prefix$READINESS_CTES\n$GLOBAL_ROW\nUNION ALL\n$WORKSPACE_ROW_MARKER$rows"
        }

        /** Appended after production's last CTE (a comment line may precede it). */
        private val READINESS_CTES =
            """
            , fp_newest AS (SELECT observed_at, started_seq, trash_bytes, block_size_bytes, capacity_bytes, written_by
                              FROM storage_filesystem_observation ORDER BY started_seq DESC, id DESC LIMIT 1),
              cp_shares AS (SELECT workspace_id FROM participant_workspace UNION ALL SELECT workspace_id FROM storage_legacy_share),
              cp_unattributed AS (
                  SELECT d.id, d.bytes, d.objects FROM storage_deletion_debt d
                   WHERE (d.workspace_id IS NULL OR NOT EXISTS (SELECT 1 FROM cp_shares s WHERE s.workspace_id = d.workspace_id))
                     AND (d.incurred_at = 'infinity' OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0))
                  UNION ALL SELECT c.debt_id, c.bytes, c.objects FROM workspace_deletion_charge c
                   WHERE NOT EXISTS (SELECT 1 FROM cp_shares s WHERE s.workspace_id = c.workspace_id)
                     AND NOT EXISTS (SELECT 1 FROM storage_deletion_debt d WHERE d.id = c.debt_id))
            """.trimIndent()

        /** The GLOBAL row: no population or global sums, the cohort-scaled readiness facts, production's column list. */
        private val GLOBAL_ROW =
            """
            SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0,
                   0::numeric AS committed, 0::numeric AS reserved, 0::numeric AS committed_objects, 0::numeric AS reserved_objects,
                   NULL::bigint AS payload_limit, NULL::bigint AS footprint_limit,
                   NULL::numeric AS cleanup_bytes, NULL::numeric AS cleanup_objects, NULL::boolean AS inactive,
                   0::numeric AS fp_live_bytes, 0::numeric AS fp_live_objects,
                   (SELECT coalesce(sum(bytes), 0) FROM cp_unattributed) AS fp_debt_bytes,
                   (SELECT coalesce(sum(objects), 0) FROM cp_unattributed) AS fp_debt_objects,
                   coalesce((SELECT trusted_epoch IS NOT DISTINCT FROM distrust_epoch FROM storage_footprint_trust WHERE id = 1), false)
                     AS fp_trusted,
                   coalesce((SELECT compacted_through_seq FROM storage_debt_watermark WHERE id = 1), 0) AS fp_watermark,
                   coalesce((SELECT distrusted_seq FROM storage_footprint_trust WHERE id = 1), 0) AS fp_distrusted_seq,
                   (SELECT started_seq FROM fp_newest) AS fp_started_seq,
                   (SELECT trash_bytes FROM fp_newest) AS fp_trash_bytes,
                   (SELECT block_size_bytes FROM fp_newest) AS fp_block_size,
                   (SELECT capacity_bytes FROM fp_newest) AS fp_capacity,
                   (SELECT written_by::text FROM fp_newest) AS fp_written_by,
                   jsonb_build_object(
                       'binding', (SELECT binding FROM storage_cohort_plan WHERE approved),
                       'fingerprint', (SELECT fingerprint FROM storage_cohort_plan WHERE approved),
                       'unattributedAllowance', (SELECT unattributed_allowance FROM storage_cohort_plan),
                       'trashAllowance', (SELECT trash_allowance FROM storage_cohort_plan),
                       'unsharedWithUsage', EXISTS (SELECT 1 FROM workspace_storage_account a
                                                     WHERE (a.base_bytes <> 0 OR a.base_objects <> 0)
                                                       AND NOT EXISTS (SELECT 1 FROM cp_shares s WHERE s.workspace_id = a.workspace_id)),
                       'nodesReady', coalesce((SELECT NOT EXISTS (
                           SELECT 1 FROM unnest(p.expected_nodes) expected WHERE NOT EXISTS (
                               SELECT 1 FROM storage_node n WHERE n.node_id = expected AND NOT n.clean_shutdown
                                  AND n.heartbeat_at >= now() - interval '5 minutes' AND n.cohort_capability = 'cohort-storage-v1'
                                  AND n.cohort_mode = 'ALL' AND n.cohort_binding = p.binding))
                           AND NOT EXISTS (SELECT 1 FROM storage_node n WHERE NOT n.clean_shutdown AND
                               (n.cohort_capability IS DISTINCT FROM 'cohort-storage-v1' OR n.cohort_mode IS DISTINCT FROM 'ALL'
                                OR n.cohort_binding IS DISTINCT FROM p.binding OR NOT (n.node_id = ANY (p.expected_nodes))))
                           AND coalesce((SELECT observed_at >= now() - make_interval(secs => p.observation_max_age_ms / 1000.0) FROM fp_newest), false)
                           FROM storage_cohort_plan p WHERE approved), false)) AS fp_cohort
            """.trimIndent()
    }
}
