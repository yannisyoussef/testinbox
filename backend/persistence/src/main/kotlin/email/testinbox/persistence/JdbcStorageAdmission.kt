package email.testinbox.persistence

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.StorageReservationDraft
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.PartitionFallbackReason
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageUsage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.core.support.AbstractSqlTypeValue
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * ADR-035 T1 on PostgreSQL (§4, §11): the global advisory lock, one
 * accounting read, the caller's decision, and one reservation insert, in one
 * transaction.
 *
 * **Deliberately not a Spring bean (TI-STORAGE-002).** Nothing in a running
 * deployable can obtain it, so no ingress path can reach it until the slice
 * that wires the fenced write path constructs it on purpose.
 *
 * - **No row locks.** T1 serializes on `(35, 1)` alone. It reads base, delta
 *   and reservation rows without locking them. The only row lock it takes is
 *   the `KEY SHARE` of its insert's FK check on `workspace`, which conflicts
 *   only with deleting a workspace or changing its key, and nothing does
 *   either.
 * - **`t0` is `now()`**, the ADR's literal choice: the transaction's start,
 *   taken just before the lock wait. A T1 that waited for the lock therefore
 *   gets a write window shorter by that wait (at most the lock timeout),
 *   which is conservative for the fence.
 * - **Other writers need not take the lock.** Every other path only lowers
 *   usage or moves it between the three sums (T2, the compactor, retention,
 *   cleanup), and that can only make a concurrent decision conservative.
 * - **Open for test mutants only.** The persistence suite overrides the
 *   protected steps to prove that each of them matters: a bypassed lock
 *   over-admits, and a split read under-counts. Production code never
 *   subclasses this.
 *
 * **Lock protocol ([locking]).** [AdmissionLocking.GLOBAL], the default, is
 * ADR-035 mode a exactly as above: `(35, 1)` EXCLUSIVE, one statement with the
 * global sums. [AdmissionLocking.COHORT_PARTITIONED] is the share-partitioned T1
 * of the PROPOSED amendment note (gate D, W1;
 * `docs/adr/0035-benchmark/partitioned-t1-correctness.md`): one exclusive
 * `(38, k)` per involved workspace in ascending `k`, then `(35, 1)` SHARED, then ONE
 * statement without the `g_*` global sums that also evaluates the partition guard
 * in the same snapshot (V25). Where a guard condition does not hold, or a
 * per-workspace lock times out or deadlocks, that transaction ends without a write
 * and the SAME call runs the mode a transaction instead, so every such event is
 * decided exactly as mode a decides it (a per-tenant 451 would break I9). Every
 * other `(35, 1)` holder stays EXCLUSIVE and so still excludes every T1 of either
 * kind; none of them takes a `(38, k)`.
 */
open class JdbcStorageAdmission(
    private val jdbc: JdbcClient,
    /** Explicit rather than `@Transactional`, for the reason `JdbcStorageLedger` gives. */
    private val transactions: TransactionOperations,
    /**
     * ADR-035 §4: 5 s. Tests shorten it to exercise the same timeout path
     * quickly. The production value is the default, and nothing overrides it.
     */
    private val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
    /**
     * TI-STORAGE-006E PR D: read the footprint inputs (debt, observations,
     * watermark, trust) in the same statement. Only where the deployment
     * declares its filesystem: an undeclared OFF deployment's roles may hold no
     * privilege on those tables, and T1 must never need one. Readable so the
     * §11 benchmark can prove it assembled the production T1 (gate D).
     */
    val readsFootprint: Boolean = false,
    /**
     * T1's lock protocol (`testinbox.storage.admission-locking`). GLOBAL, mode a,
     * unless declared. COHORT_PARTITIONED needs [readsFootprint]: its guard and its
     * decision both rest on the cohort pool that statement reads.
     */
    val locking: AdmissionLocking = AdmissionLocking.GLOBAL,
    /** Told why each share-partitioned T1 was decided by mode a (the closed-label fallback metric). */
    private val onFallback: (PartitionFallbackReason) -> Unit = {},
    /**
     * The slot-lane escrow strategy (ADR-038 PROPOSED; M1 prototype), or null: mode a,
     * byte for byte (`ModeASequenceTest`, B-7). With one, this store is the escrow's
     * SLOW path: the drain fence instead of `(35, 1)` alone, the event's scope rows,
     * mode a's snapshot statement byte-identical, mode a's decision, the insert, and
     * THEN the regrant (H1), in one transaction. Only the storage benchmark passes one.
     */
    private val escrow: EscrowSlowPath? = null,
) : StorageAdmissionStore {
    init {
        require(escrow == null || (locking == AdmissionLocking.GLOBAL && readsFootprint)) {
            "the slot-lane escrow's slow path is mode a with GLOBAL locking and the footprint read"
        }
        // Whole milliseconds: '0ms' would mean NO timeout, an unbounded wait.
        require(lockTimeout.toMillis() in 1..Int.MAX_VALUE.toLong()) { "the admission lock timeout must be 1 ms or more, was $lockTimeout" }
        require(locking == AdmissionLocking.GLOBAL || readsFootprint) {
            "the share-partitioned T1 reads the cohort pool: it needs readsFootprint (a declared filesystem)"
        }
    }

    private val partitionedCount = AtomicLong()
    private val fallbackCount = AtomicLong()

    /** T1s decided on the share-partitioned path. Read-only: the benchmark probe and the tests. */
    val partitionedAdmissions: Long get() = partitionedCount.get()

    /** Share-partitioned T1s whose guard did not hold, decided by mode a instead. Read-only, like the above. */
    val partitionFallbacks: Long get() = fallbackCount.get()

    final override fun <R : Any> admit(
        scope: StorageAdmissionScope,
        decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
    ): R {
        // T1 must be its own transaction. Joined to a caller's, it would hold
        // (35, 1) until the caller commits, possibly across uploads, and its
        // reservations would stay uncommitted while objects are written. An
        // outer READ COMMITTED transaction would not even make the isolation
        // statement fail, so this is checked, not assumed.
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "storage admission (T1) must not run inside another transaction"
        }
        if (locking == AdmissionLocking.COHORT_PARTITIONED) {
            when (val attempt = bounded { attemptPartitioned(scope, decide) }) {
                is Attempt.Decided -> {
                    partitionedCount.incrementAndGet()
                    return attempt.outcome
                }

                // Nothing was written and every lock is released: mode a decides, in a new transaction.
                is Attempt.FellBack -> {
                    fallbackCount.incrementAndGet()
                    onFallback(attempt.reason)
                }
            }
        }
        val slow = escrow
        if (slow != null) return bounded { checkNotNull(transactions.execute { escrowSlowPath(slow, scope, decide) }) }
        return bounded {
            checkNotNull(
                transactions.execute {
                    configureTransaction()
                    acquireAdmissionLock()
                    val plan = decide(readSnapshot(scope))
                    insert(plan.reservations)
                    plan.outcome
                },
            )
        }
    }

    /** The application inputs of this store's escrow strategy, or null under mode a. */
    val escrowInputs: email.testinbox.application.port.EscrowInputs? get() = escrow?.inputs

    /**
     * The escrow's slow path (§4.4), steps 1–8 in one transaction: configure, the
     * drain fence, the event's scope rows, mode a's snapshot statement and mode a's
     * decision, the insert WITHOUT its batched COMMIT, then the regrant, whose truth
     * includes that insert (H1). The template commits.
     */
    private fun <R : Any> escrowSlowPath(
        slow: EscrowSlowPath,
        scope: StorageAdmissionScope,
        decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
    ): R {
        configureTransaction()
        slow.escrowDrainFence()
        slow.escrowLockScopes(scope)
        val plan = decide(readSnapshot(scope))
        insert(plan.reservations, commit = false)
        slow.escrowRegrant(scope, plan.escrowNotReady)
        return plan.outcome
    }

    /**
     * The keeper's refill-only slow path (§4.5): steps 1, 2, 4 and 7 with no event.
     * Mode a's snapshot statement runs with no ids (its global row only), and
     * [notReady] is the application's readiness verdict on it.
     */
    fun escrowRefillOnly(notReady: (StorageUsageSnapshot) -> String?): email.testinbox.application.port.EscrowRegrant {
        val slow = checkNotNull(escrow) { "refill-only runs on the escrow's slow path" }
        check(
            !TransactionSynchronizationManager.isActualTransactionActive(),
        ) { "the escrow refill must not run inside another transaction" }
        return bounded {
            checkNotNull(
                transactions.execute {
                    configureTransaction()
                    slow.escrowDrainFence()
                    slow.escrowRegrant(null, notReady(snapshot(readRows(emptyList(), emptyList()))))
                },
            )
        }
    }

    private inline fun <T> bounded(block: () -> T): T =
        try {
            block()
        } catch (failure: RuntimeException) {
            if (isInfrastructureTimeout(failure)) {
                throw StorageAdmissionUnavailableException("a T1 wait exceeded its bound (lock timeout $lockTimeout)", failure)
            }
            throw failure
        }

    /** A partitioned decision, or the reason mode a decides instead. */
    private sealed interface Attempt<out R : Any> {
        class Decided<R : Any>(
            val outcome: R,
        ) : Attempt<R>

        class FellBack(
            val reason: PartitionFallbackReason,
        ) : Attempt<Nothing>
    }

    /** A per-workspace lock that could not be taken: never a 451 attributable to one workspace (I9). */
    private class WorkspaceLockFallback(
        val reason: PartitionFallbackReason,
        cause: Throwable,
    ) : RuntimeException(cause)

    /**
     * The share-partitioned transaction: the per-workspace locks in ascending key
     * order, then `(35, 1)` SHARED, ONE statement, the caller's decision, the
     * insert. A fallback, with the transaction rolled back before any write, when
     * a guard condition read in that statement does not hold, or when a
     * per-workspace lock times out or is broken as a deadlock: that wait depends
     * on one workspace's load, so its 451 would tell a sender which workspace an
     * address belongs to (I9, ADR-025). The shared `(35, 1)` is everyone's, like
     * mode a's exclusive one, so its timeout stays the global 451 it is in mode a.
     */
    private fun <R : Any> attemptPartitioned(
        scope: StorageAdmissionScope,
        decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
    ): Attempt<R> =
        try {
            checkNotNull(
                transactions.execute { status ->
                    configureTransaction()
                    // The workspace locks FIRST, then the shared (35, 1): a T1 queued behind a
                    // busy workspace holds no (35, 1), so an exclusive (35, 1) asker (a heartbeat's
                    // node fence, rule P, a fallback) and, behind it, every other workspace's T1
                    // never wait for that queue. No exclusive (35, 1) holder takes a (38, k), so
                    // no cycle (partitioned-t1-correctness.md §3).
                    try {
                        acquireWorkspaceLocks(workspaceLockKeys(scope.workspaceIds.map { it.value }))
                    } catch (failure: RuntimeException) {
                        throw WorkspaceLockFallback(workspaceLockFailure(failure) ?: throw failure, failure)
                    }
                    acquireSharedAdmissionLock()
                    val (snapshot, refused) = readPartitioned(scope)
                    if (refused != null) {
                        status.setRollbackOnly()
                        Attempt.FellBack(refused)
                    } else {
                        val plan = decide(snapshot)
                        insert(plan.reservations)
                        Attempt.Decided(plan.outcome)
                    }
                },
            )
        } catch (fallback: WorkspaceLockFallback) {
            Attempt.FellBack(fallback.reason)
        }

    private fun workspaceLockFailure(failure: Throwable): PartitionFallbackReason? {
        val states = generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }.toSet()
        return when {
            DEADLOCK_DETECTED in states -> PartitionFallbackReason.WORKSPACE_LOCK_DEADLOCK
            states.any { it in TIMEOUT_STATES } -> PartitionFallbackReason.WORKSPACE_LOCK_TIMEOUT
            else -> null
        }
    }

    /**
     * Stated, never inherited from a server or pool default (ADR-035 §4):
     * - READ COMMITTED, so the snapshot statement starts after the lock is
     *   granted;
     * - `synchronous_commit = on`, because a reservation acknowledged and
     *   then lost in a crash would free bytes that a write may still fill;
     * - a lock timeout;
     * - a statement timeout and an idle-in-transaction timeout, so that a
     *   stalled or vanished client cannot hold the global lock, and with it
     *   every tenant's admission, until TCP keepalive notices;
     * - `plan_cache_mode = force_generic_plan` (gate D step (1)): the snapshot
     *   is ONE fixed text with one `uuid[]` bind per id list, so the driver
     *   server-prepares it after its prepare threshold and PostgreSQL plans it
     *   once per connection, never per admission inside the lock. A generic
     *   plan cannot depend on the bound ids, which is what a fixed-arity
     *   statement over in-flight tables wants.
     *
     * The isolation level must be the transaction's first statement.
     */
    private fun configureTransaction() {
        jdbc.sql("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").update()
        // SET takes no bind parameters. Every value below is a validated Long, never input.
        // Gate D: planning was about half of the statement under the lock at c10dd3d (#124 6633013793).
        jdbc
            .sql(
                "SET LOCAL synchronous_commit = on; SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'; " +
                    "SET LOCAL plan_cache_mode = force_generic_plan",
            ).update()
        jdbc
            .sql(
                "SET LOCAL statement_timeout = '${lockTimeout.plus(STATEMENT_MARGIN).toMillis()}ms'; " +
                    "SET LOCAL idle_in_transaction_session_timeout = '${IDLE_IN_TRANSACTION.toMillis()}ms'",
            ).update()
    }

    /** The global admission lock: ADR-035's two-`int4` key space, never ADR-027's one-`bigint` guard. */
    protected open fun acquireAdmissionLock() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock(:class, :admission)")
            .param("class", STORAGE_LOCK_CLASS)
            .param("admission", ADMISSION_LOCK)
            .query()
            .singleRow()
    }

    /**
     * Partitioned: the same key, SHARED. It conflicts only with an EXCLUSIVE
     * holder — mode a's T1, rule (P), the lifecycle drain fence, the node fence,
     * pool reserve/revoke, beta admission and the V25 share-table fence — so each
     * of those still excludes, and is excluded by, every partitioned T1.
     * PostgreSQL queues a new shared request behind a waiting exclusive one, so a
     * stream of partitioned T1s cannot starve those writers. Taken AFTER the
     * workspace locks, and held only from there to commit.
     */
    protected open fun acquireSharedAdmissionLock() {
        jdbc
            .sql("SELECT pg_advisory_xact_lock_shared(:class, :admission)")
            .param("class", STORAGE_LOCK_CLASS)
            .param("admission", ADMISSION_LOCK)
            .query()
            .singleRow()
    }

    /**
     * Partitioned: one EXCLUSIVE `(38, k)` per distinct key of the involved
     * workspaces, ONE STATEMENT PER KEY, in the ascending order of the keys
     * themselves ([workspaceLockKeys]). The order is the program's, never an
     * executor's, so every T1 acquires its keys in one global order and two T1s
     * cannot wait for each other's keys (no cycle). A key collision only
     * serializes two workspaces. At most one statement per distinct workspace of
     * the event (≤ 50), all outside any global exclusive lock.
     */
    protected open fun acquireWorkspaceLocks(keys: List<Int>) {
        for (key in keys) {
            jdbc
                .sql("SELECT pg_advisory_xact_lock(:class, :key)")
                .param("class", WORKSPACE_LOCK_CLASS)
                .param("key", key)
                .query()
                .singleRow()
        }
    }

    /**
     * ONE statement, so ONE snapshot (ADR-035 §4, "Why one statement").
     *
     * T2 moves bytes from a reservation into the ledger, and the compactor
     * moves them from a delta into a base row. Each move is atomic. This
     * statement sees each one wholly before or wholly after. A second
     * statement could see the reservation already gone while an earlier
     * statement had not yet seen its delta, and under-count exactly those
     * bytes. `StorageAdmissionSnapshotTest` shows that window with a split
     * read.
     *
     * Every figure is `base + Σdelta + Σreservation`. A missing workspace or
     * inbox base row reads as 0, and its deltas and reservations still count.
     * Every reservation counts, whatever its state or age (I5). The GLOBAL
     * base is the V24 `storage_global_base` row, kept equal to Σ workspace
     * bases by definer triggers in the base writers' own transactions; a
     * missing row is never read as 0 (see [FootprintSql.GLOBAL_CTES]).
     *
     * Gate D A′: the global and per-workspace reservation sums read V27's stored
     * `object_count` (`cardinality(object_keys)`, computed by PostgreSQL), so both
     * can be answered from the covering `ix_storage_reservation_usage`. The per-inbox
     * sum keeps `cardinality(object_keys)`: no inbox index carries the count, so it
     * reads the same heap rows either way.
     */
    private val footprintCtes = if (readsFootprint) ",\n${FootprintSql.FOOTPRINT_CTES}" else ""
    private val footprintColumns = if (readsFootprint) FootprintSql.COLUMNS else FootprintSql.NONE

    /** Mode a's global sums (V24 base row, one scan per in-flight table), between `ib` and the local CTEs. */
    private val globalCtes =
        """
             -- Each global sum is ONE scan of its table, bytes and objects together
             -- (referenced more than once, so materialized once), still in the same
             -- statement. Σ workspace bases is ONE row (V24), never the population.
             ${FootprintSql.GLOBAL_CTES},"""

    /** Mode a's GLOBAL row: base + Σ delta + Σ reservation over every workspace. */
    private val globalRow =
        """-- Objects beside bytes (TI-STORAGE-006E): the footprint bound is
        -- applied by the caller, from the same snapshot.
        SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0,
               (SELECT bytes FROM g_base) + (SELECT bytes FROM g_delta) AS committed,
               (SELECT bytes FROM g_reserved) AS reserved,
               (SELECT objects FROM g_base) + (SELECT objects FROM g_delta) AS committed_objects,
               (SELECT objects FROM g_reserved) AS reserved_objects,
               NULL::bigint AS payload_limit, NULL::bigint AS footprint_limit,
               NULL::numeric AS cleanup_bytes,NULL::numeric AS cleanup_objects,
               NULL::boolean AS inactive,
               $footprintColumns"""

    /**
     * The statement's exact text, with its binds `:workspaces` and `:inboxes`.
     * Readable so the §11 benchmark can record and EXPLAIN exactly what
     * production runs (gate D evidence); nothing else may build SQL from it.
     */
    val snapshotStatement: String = statement(globalCtes, globalRow, guardColumn = "")

    /**
     * The share-partitioned statement ([AdmissionLocking.COHORT_PARTITIONED]): the
     * same per-workspace and per-inbox text, no global sum, and the partition
     * guard as one more column, all in ONE snapshot. Null under mode a, whose
     * statement may not even have the privileges this one needs. Readable for
     * the benchmark, like [snapshotStatement].
     */
    val partitionedStatement: String? by lazy {
        if (locking == AdmissionLocking.COHORT_PARTITIONED) {
            statement("", PartitionedT1Sql.globalRow(partitionGuard()), guardColumn = PartitionedT1Sql.NO_GUARD)
        } else {
            null
        }
    }

    /**
     * The partition guard's four columns ([PartitionedT1Sql]): G1 an approved
     * plan with its own recorded bound, G2 not overcommitted, G3 every involved
     * workspace shared, G4 no uncharged debt of a shared workspace. A test mutant
     * overrides this to prove each condition is load-bearing; the architecture
     * suite forbids any main-code subclass from doing so.
     */
    protected open fun partitionGuard(): String = PartitionedT1Sql.columns()

    /**
     * The ONE statement's text, built from the parts mode a and the partitioned
     * T1 share: the per-workspace and per-inbox CTEs and rows are identical in
     * both. Mode a's text is pinned byte for byte (`AdmissionStatementTextTest`).
     */
    private fun statement(
        globalCtes: String,
        globalRow: String,
        guardColumn: String,
    ): String =
        """
        WITH ws AS (SELECT DISTINCT unnest(CAST(:workspaces AS uuid[])) AS id),
             ib AS (SELECT DISTINCT unnest(CAST(:inboxes AS uuid[])) AS id),$globalCtes
             -- Each sum is aggregated once for the involved ids, not
             -- once per id: the statement's cost must not grow with
             -- the recipient count times the ledger backlog.
             ws_delta AS (SELECT d.workspace_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                           WHERE d.workspace_id IN (SELECT id FROM ws) GROUP BY d.workspace_id),
             ws_reserved AS (SELECT r.workspace_id AS id, sum(r.bytes) AS bytes, sum(r.object_count) AS objects
                               FROM storage_reservation r
                              WHERE r.workspace_id IN (SELECT id FROM ws) GROUP BY r.workspace_id),
             ws_charge AS (SELECT workspace_id AS id,sum(bytes) AS bytes,sum(objects) AS objects
                              FROM workspace_deletion_charge WHERE workspace_id IN (SELECT id FROM ws) GROUP BY workspace_id),
             ib_delta AS (SELECT d.inbox_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                           WHERE d.inbox_id IN (SELECT id FROM ib) GROUP BY d.inbox_id),
             ib_reserved AS (SELECT r.inbox_id AS id, sum(r.bytes) AS bytes, sum(cardinality(r.object_keys)) AS objects
                               FROM storage_reservation r
                              WHERE r.inbox_id IN (SELECT id FROM ib) GROUP BY r.inbox_id)$footprintCtes
        $globalRow
        UNION ALL
        SELECT 'WORKSPACE', ws.id, ws.id, now(),
               coalesce(a.base_bytes, 0) + coalesce(d.bytes, 0),
               coalesce(r.bytes, 0),
               coalesce(a.base_objects, 0) + coalesce(d.objects, 0),
               coalesce(r.objects, 0),
               coalesce(p.payload_limit_bytes,l.payload_limit_bytes),coalesce(p.footprint_limit_bytes,l.footprint_limit_bytes),
               coalesce(c.bytes,0),coalesce(c.objects,0),
               -- Account lifecycle (V22), in the SAME statement under (35, 1):
               -- a participant marker without an active membership is inactive.
               coalesce(NOT e.active, false),
               ${FootprintSql.NONE}$guardColumn
          FROM ws
          LEFT JOIN workspace_storage_account a ON a.workspace_id = ws.id
          LEFT JOIN ws_delta d ON d.id = ws.id
          LEFT JOIN ws_reserved r ON r.id = ws.id
          LEFT JOIN ws_charge c ON c.id=ws.id
          LEFT JOIN participant_workspace p ON p.workspace_id=ws.id
          LEFT JOIN storage_legacy_share l ON l.workspace_id=ws.id
          LEFT JOIN workspace_lifecycle_eligibility e ON e.workspace_id = ws.id
        UNION ALL
        SELECT 'INBOX', ib.id, x.workspace_id, now(),
               coalesce(s.base_bytes, 0) + coalesce(d.bytes, 0),
               coalesce(r.bytes, 0),
               coalesce(s.base_objects, 0) + coalesce(d.objects, 0),
               coalesce(r.objects, 0),
               NULL::bigint,NULL::bigint,NULL::numeric,NULL::numeric,NULL::boolean,
               ${FootprintSql.NONE}$guardColumn
          FROM ib
          LEFT JOIN inbox x ON x.id = ib.id
          LEFT JOIN inbox_storage s ON s.inbox_id = ib.id
          LEFT JOIN ib_delta d ON d.id = ib.id
          LEFT JOIN ib_reserved r ON r.id = ib.id
        """.trimIndent()

    protected open fun readSnapshot(scope: StorageAdmissionScope): StorageUsageSnapshot =
        snapshot(readRows(scope.workspaceIds.map { it.value }, scope.inboxIds.map { it.value }))

    private fun readRows(
        workspaces: List<UUID>,
        inboxes: List<UUID>,
    ): List<SnapshotRow> =
        jdbc
            .sql(snapshotStatement)
            .param("workspaces", uuidArray(workspaces), Types.ARRAY)
            .param("inboxes", uuidArray(inboxes), Types.ARRAY)
            .query { rs, _ -> snapshotRow(rs) }
            .list()

    /**
     * The partitioned statement, ONE snapshot: the figures, and the first guard
     * condition that does not hold in that snapshot (null when the partition holds).
     */
    private fun readPartitioned(scope: StorageAdmissionScope): Pair<StorageUsageSnapshot, PartitionFallbackReason?> {
        var guard: List<Boolean?> = emptyList()
        val rows =
            jdbc
                .sql(checkNotNull(partitionedStatement))
                .param("workspaces", uuidArray(scope.workspaceIds.map { it.value }), Types.ARRAY)
                .param("inboxes", uuidArray(scope.inboxIds.map { it.value }), Types.ARRAY)
                .query { rs, _ ->
                    if (rs.getString("scope") == "GLOBAL") {
                        guard = PartitionedT1Sql.GUARD_COLUMN_NAMES.map { rs.getBoolean(it).takeUnless { rs.wasNull() } }
                    }
                    snapshotRow(rs)
                }.list()
        return snapshot(rows) to PartitionedT1Sql.reason(guard)
    }

    private fun snapshot(rows: List<SnapshotRow>): StorageUsageSnapshot {
        val global = rows.single { it.scope == "GLOBAL" }
        return StorageUsageSnapshot(
            t0 = global.t0,
            global = global.usage,
            workspaces = rows.filter { it.scope == "WORKSPACE" }.associate { WorkspaceId(it.id!!) to it.usage },
            inboxes =
                rows
                    .filter { it.scope == "INBOX" }
                    .associate { InboxId(it.id!!) to InboxStorageUsage(it.owner?.let(::WorkspaceId), it.usage) },
            footprint = global.footprint,
            budgets =
                rows.filter { it.scope == "WORKSPACE" && it.budget != null }.associate {
                    WorkspaceId(
                        it.id!!,
                    ) to checkNotNull(it.budget)
                },
            inactiveWorkspaces = rows.filter { it.scope == "WORKSPACE" && it.inactive }.mapTo(HashSet()) { WorkspaceId(it.id!!) },
        )
    }

    /** One `uuid[]` bind: the statement text no longer depends on how many ids an event carries. */
    private fun uuidArray(ids: List<UUID>): AbstractSqlTypeValue =
        object : AbstractSqlTypeValue() {
            override fun createTypeValue(
                connection: Connection,
                sqlType: Int,
                typeName: String?,
            ): Any = connection.createArrayOf("uuid", ids.toTypedArray())
        }

    private fun snapshotRow(rs: java.sql.ResultSet): SnapshotRow =
        SnapshotRow(
            footprint = if (readsFootprint && rs.getString("scope") == "GLOBAL") FootprintSql.read(rs) else null,
            scope = rs.getString("scope"),
            inactive = rs.getBoolean("inactive"),
            budget =
                if (rs.getObject("payload_limit") ==
                    null
                ) {
                    null
                } else {
                    email.testinbox.application.storage.WorkspaceStorageBudget(
                        rs.getLong("payload_limit"),
                        rs.getLong("footprint_limit"),
                        email.testinbox.domain.storage.FootprintAdmission.Load(
                            exactLong(rs.getBigDecimal("cleanup_bytes")),
                            exactLong(rs.getBigDecimal("cleanup_objects")),
                        ),
                    )
                },
            id = rs.getObject("id", UUID::class.java),
            owner = rs.getObject("owner", UUID::class.java),
            t0 = checkNotNull(Timestamps.fromDb(rs, "t0")),
            usage =
                StorageUsage(
                    exactLong(checkNotNull(rs.getBigDecimal("committed")) { "a committed figure is NULL: corrupt accounting" }),
                    exactLong(rs.getBigDecimal("reserved")),
                    // Counts can only drift negative through corruption; a bound cannot carry that.
                    maxOf(
                        0L,
                        exactLong(checkNotNull(rs.getBigDecimal("committed_objects")) { "a committed figure is NULL: corrupt accounting" }),
                    ),
                    maxOf(0L, exactLong(rs.getBigDecimal("reserved_objects"))),
                ),
        )

    /**
     * One multi-row insert. It is bounded by the 50-recipient event cap, and
     * it commits or fails as a whole with T1, so an event's admitted set is
     * never partly reserved.
     *
     * **The insert and T1's COMMIT travel in ONE round trip** (gate D A′, owner
     * decision 2026-11-06, #124 6684712608): the driver sends both statements
     * before it reads a reply, so `(35, 1)` is released by the COMMIT right
     * after the insert instead of a client round trip later. Nothing about the
     * transaction changes: the same insert, then the same COMMIT, in the same
     * server transaction, under the same `synchronous_commit = on`; the lock is
     * still released only by that COMMIT. If the insert fails, PostgreSQL skips
     * the COMMIT (an error discards the rest of the batch), the transaction is
     * aborted and the template rolls it back, exactly as before. Once the batch
     * has committed, the template's own commit finds no open transaction and
     * the driver sends nothing.
     *
     * The row count is checked after the COMMIT now. It can only be
     * `reservations.size`: a multi-row `INSERT … VALUES` without `ON CONFLICT`
     * inserts every row or raises. Were it ever different, T1 would still fail
     * the event closed, its reservations counting until cleanup releases them
     * (an over-count, never an under-count).
     */
    private fun insert(
        reservations: List<StorageReservationDraft>,
        /** False only on the escrow's slow path, whose regrant must follow the insert inside T1. */
        commit: Boolean = true,
    ) {
        if (reservations.isEmpty()) return
        val end = if (commit) ";\n                COMMIT" else ""
        val values =
            reservations.indices.joinToString(",\n") { i ->
                "(:m$i, :w$i, :i$i, ARRAY[:k$i]::text[], :b$i, 'RESERVED', :c$i, :d$i, :n$i, :g$i)"
            }
        var statement =
            jdbc.sql(
                """
                INSERT INTO storage_reservation
                    (message_id, workspace_id, inbox_id, object_keys, bytes, state, created_at, write_deadline_at, node_id, generation)
                VALUES
                $values$end
                """.trimIndent(),
            )
        reservations.forEachIndexed { i, r ->
            statement =
                statement
                    .param("m$i", r.messageId.value)
                    .param("w$i", r.workspaceId.value)
                    .param("i$i", r.inboxId.value)
                    .param("k$i", r.objectKeys)
                    .param("b$i", r.bytes)
                    .param("c$i", Timestamps.toDb(r.createdAt))
                    .param("d$i", Timestamps.toDb(r.writeDeadlineAt))
                    .param("n$i", r.nodeId)
                    .param("g$i", r.generation)
        }
        val inserted = statement.update()
        check(inserted == reservations.size) { "inserted $inserted of ${reservations.size} reservations" }
    }

    /** numeric → Long, exactly: a sum beyond 64 bits fails closed, classified as the rules classify it. */
    private fun exactLong(value: java.math.BigDecimal): Long =
        try {
            value.longValueExact()
        } catch (overflow: ArithmeticException) {
            throw StorageAccountingOverflowException("stored usage exceeds a signed 64-bit figure", overflow)
        }

    /** A bounded wait ran out: infrastructure, never capacity. */
    private fun isInfrastructureTimeout(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState in TIMEOUT_STATES }

    private data class SnapshotRow(
        val footprint: email.testinbox.application.port.ObservedFootprint?,
        val budget: email.testinbox.application.storage.WorkspaceStorageBudget?,
        val scope: String,
        val inactive: Boolean,
        val id: UUID?,
        val owner: UUID?,
        val t0: java.time.Instant,
        val usage: StorageUsage,
    )

    companion object {
        val DEFAULT_LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)

        /** ADR-035's advisory class and ids: (35, 1) admission, (35, 2) ledger (`JdbcStorageLedger`). */
        const val STORAGE_LOCK_CLASS = 35
        const val ADMISSION_LOCK = 1

        /**
         * The share-partitioned T1's per-workspace class (PROPOSED, gate D W1):
         * `(38, k)`. No other lock in the code base uses class 38, and the
         * two-`int4` key space is disjoint from ADR-027's one-`bigint` guards.
         */
        const val WORKSPACE_LOCK_CLASS = 38

        /**
         * Distinct, and ascending by the KEY itself: the one global acquisition
         * order that makes the per-workspace locks deadlock-free. Deduplicating and
         * sorting the keys (not the workspaces) is what makes a collision safe: two
         * colliding workspaces of one event take their one shared key once, and
         * across events every key still comes in the same order. `UUID.hashCode` is
         * specified by the JDK (the XOR of the four 32-bit words), so every node
         * computes the same key for a workspace; a collision only serializes.
         */
        fun workspaceLockKeys(workspaces: Collection<UUID>): List<Int> = workspaces.map { it.hashCode() }.distinct().sorted()

        /** How much longer than the lock timeout any one T1 statement may run. */
        private val STATEMENT_MARGIN: Duration = Duration.ofSeconds(5)

        /** How long T1 may sit idle between its statements, e.g. a stalled JVM, before PostgreSQL ends it. */
        val IDLE_IN_TRANSACTION: Duration = Duration.ofSeconds(10)

        /**
         * PostgreSQL's `lock_not_available` (lock_timeout), `query_canceled`
         * (statement_timeout) and `idle_in_transaction_session_timeout`.
         */
        private val TIMEOUT_STATES = setOf("55P03", "57014", "25P03")

        /** PostgreSQL's `deadlock_detected`. */
        private const val DEADLOCK_DETECTED = "40P01"
    }
}
