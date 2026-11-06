package email.testinbox.persistence

import email.testinbox.application.storage.PartitionFallbackReason

/**
 * The SQL that only the share-partitioned T1 runs (PROPOSED ADR-035 amendment,
 * gate D W1; `docs/adr/0035-benchmark/partitioned-t1-correctness.md`): its GLOBAL
 * row and the partition guard, one boolean column per condition, so a fallback
 * to mode a names its reason ([PartitionFallbackReason]). Every condition is
 * evaluated in the T1 statement's own snapshot; the V25 function is `STABLE`, so
 * it reads that snapshot too.
 */
internal object PartitionedT1Sql {
    /** G1: an APPROVED plan whose recorded V25 bound carries the plan's exact binding and fingerprint. */
    const val PLAN = """coalesce((SELECT p.approved AND b.binding = p.binding AND b.fingerprint = p.fingerprint
                         FROM storage_cohort_plan p JOIN storage_partition_bound b ON b.id
                        WHERE p.id), false)"""

    /** G2: thirty participant places and every legacy share within that bound (not overcommitted). */
    const val BOUND = "storage_partition_within_bound()"

    /** G3: every workspace of this event holds a share (`cp_shares` is [CohortSql]'s inventory CTE). */
    const val SHARES = "NOT EXISTS (SELECT 1 FROM ws WHERE NOT EXISTS (SELECT 1 FROM cp_shares s WHERE s.workspace_id = ws.id))"

    /**
     * G4: no unsuperseded debt row (pending, or ordered at or after the newest
     * observation, exactly `fp_debt`'s filter) belongs to a shared workspace and
     * is charged to no share. Such a row exists when the debt's transaction raced
     * the reserve that shared its workspace: the V20 attribution trigger saw no
     * share and the reserve's back-fill could not see the uncommitted row. It is
     * then in neither a share's cleanup nor the unattributed pool, so the partition
     * would not bound it; mode a's *D* still counts it.
     */
    const val DEBT = """NOT EXISTS (SELECT 1 FROM storage_deletion_debt d
                                 WHERE (d.incurred_at = 'infinity'::timestamptz
                                        OR d.seq >= coalesce((SELECT started_seq FROM fp_newest), 0))
                                   AND EXISTS (SELECT 1 FROM cp_shares s WHERE s.workspace_id = d.workspace_id)
                                   AND NOT EXISTS (SELECT 1 FROM workspace_deletion_charge c WHERE c.debt_id = d.id))"""

    /** The guard columns, in the order [reason] reads them. */
    fun columns(
        plan: String = PLAN,
        bound: String = BOUND,
        shares: String = SHARES,
        debt: String = DEBT,
    ): String = "$plan AS g_plan,\n               $bound AS g_bound,\n               $shares AS g_shares,\n               $debt AS g_debt"

    /** The same four columns, typed, for the WORKSPACE and INBOX rows. */
    const val NO_GUARD = ", NULL::boolean, NULL::boolean, NULL::boolean, NULL::boolean"

    /** The first guard condition that does not hold, or null when the partition holds. */
    fun reason(guard: List<Boolean?>): PartitionFallbackReason? = guard.zip(ORDER).firstOrNull { (held, _) -> held != true }?.second

    private val ORDER =
        listOf(
            PartitionFallbackReason.NO_MATCHING_BOUND,
            PartitionFallbackReason.OVER_BOUND,
            PartitionFallbackReason.UNSHARED_WORKSPACE,
            PartitionFallbackReason.UNCHARGED_DEBT,
        )
    val GUARD_COLUMN_NAMES = listOf("g_plan", "g_bound", "g_shares", "g_debt")

    /**
     * The GLOBAL row: no `g_*` global sums (L = 0, global payload 0). Every live
     * and reserved byte belongs to a share the guard proves fits the declarations,
     * so the global ceilings hold by that partition. NOT "no global scan": the
     * cohort pool's per-share usage ([CohortSql]) still reads every SHARED
     * workspace's deltas and reservations (gate D A1), `fp_debt` the debt table.
     */
    fun globalRow(guard: String): String =
        """-- Share-partitioned T1 (PROPOSED amendment, gate D W1): no g_* global sums. Every
        -- live and reserved byte belongs to a share the guard below proves fits the
        -- declarations, so the global ceilings hold by that partition, not by these
        -- figures (docs/adr/0035-benchmark/partitioned-t1-correctness.md). Debt, trash,
        -- trust, the observation and the WHOLE cohort pool are still read, and decided.
        SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0,
               0::numeric AS committed, 0::numeric AS reserved,
               0::numeric AS committed_objects, 0::numeric AS reserved_objects,
               NULL::bigint AS payload_limit, NULL::bigint AS footprint_limit,
               NULL::numeric AS cleanup_bytes,NULL::numeric AS cleanup_objects,
               NULL::boolean AS inactive,
               ${FootprintSql.PARTITIONED_COLUMNS},
               $guard"""
}
