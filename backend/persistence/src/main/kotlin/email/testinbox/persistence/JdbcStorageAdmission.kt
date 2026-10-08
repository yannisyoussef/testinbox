package email.testinbox.persistence

import email.testinbox.application.port.InboxStorageUsage
import email.testinbox.application.port.StorageAccountingOverflowException
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.StorageReservationDraft
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.StorageUsage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

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
) : StorageAdmissionStore {
    init {
        // Whole milliseconds: '0ms' would mean NO timeout, an unbounded wait.
        require(lockTimeout.toMillis() in 1..Int.MAX_VALUE.toLong()) { "the admission lock timeout must be 1 ms or more, was $lockTimeout" }
    }

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
        return try {
            checkNotNull(
                transactions.execute {
                    configureTransaction()
                    acquireAdmissionLock()
                    val plan = decide(readSnapshot(scope))
                    insert(plan.reservations)
                    plan.outcome
                },
            )
        } catch (failure: RuntimeException) {
            if (isInfrastructureTimeout(failure)) {
                throw StorageAdmissionUnavailableException("a T1 wait exceeded its bound (lock timeout $lockTimeout)", failure)
            }
            throw failure
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
     *   every tenant's admission, until TCP keepalive notices.
     *
     * The isolation level must be the transaction's first statement.
     */
    private fun configureTransaction() {
        jdbc.sql("SET TRANSACTION ISOLATION LEVEL READ COMMITTED").update()
        // SET takes no bind parameters. Every value below is a validated Long, never input.
        jdbc.sql("SET LOCAL synchronous_commit = on; SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'").update()
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
     * Every figure is `base + Σdelta + Σreservation`. A missing base row reads
     * as 0, and its deltas and reservations still count. Every reservation
     * counts, whatever its state or age (I5).
     */
    protected open fun readSnapshot(scope: StorageAdmissionScope): StorageUsageSnapshot {
        val rows =
            jdbc
                .sql(
                    """
                    WITH ws AS (SELECT DISTINCT unnest(ARRAY[:workspaces]::uuid[]) AS id),
                         ib AS (SELECT DISTINCT unnest(ARRAY[:inboxes]::uuid[]) AS id),
                         -- Each sum is aggregated once for the involved ids, not
                         -- once per id: the statement's cost must not grow with
                         -- the recipient count times the ledger backlog.
                         ws_delta AS (SELECT d.workspace_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                                       WHERE d.workspace_id IN (SELECT id FROM ws) GROUP BY d.workspace_id),
                         ws_reserved AS (SELECT r.workspace_id AS id, sum(r.bytes) AS bytes, sum(cardinality(r.object_keys)) AS objects
                                           FROM storage_reservation r
                                          WHERE r.workspace_id IN (SELECT id FROM ws) GROUP BY r.workspace_id),
                         ib_delta AS (SELECT d.inbox_id AS id, sum(d.bytes) AS bytes, sum(d.objects) AS objects FROM storage_delta d
                                       WHERE d.inbox_id IN (SELECT id FROM ib) GROUP BY d.inbox_id),
                         ib_reserved AS (SELECT r.inbox_id AS id, sum(r.bytes) AS bytes, sum(cardinality(r.object_keys)) AS objects
                                           FROM storage_reservation r
                                          WHERE r.inbox_id IN (SELECT id FROM ib) GROUP BY r.inbox_id)
                    -- Objects beside bytes (TI-STORAGE-006E): the footprint bound is
                    -- applied by the caller, from the same snapshot.
                    SELECT 'GLOBAL' AS scope, NULL::uuid AS id, NULL::uuid AS owner, now() AS t0,
                           (SELECT coalesce(sum(base_bytes), 0) FROM workspace_storage_account)
                         + (SELECT coalesce(sum(bytes), 0) FROM storage_delta) AS committed,
                           (SELECT coalesce(sum(bytes), 0) FROM storage_reservation) AS reserved,
                           (SELECT coalesce(sum(base_objects), 0) FROM workspace_storage_account)
                         + (SELECT coalesce(sum(objects), 0) FROM storage_delta) AS committed_objects,
                           (SELECT coalesce(sum(cardinality(object_keys)), 0) FROM storage_reservation) AS reserved_objects
                    UNION ALL
                    SELECT 'WORKSPACE', ws.id, ws.id, now(),
                           coalesce(a.base_bytes, 0) + coalesce(d.bytes, 0),
                           coalesce(r.bytes, 0),
                           coalesce(a.base_objects, 0) + coalesce(d.objects, 0),
                           coalesce(r.objects, 0)
                      FROM ws
                      LEFT JOIN workspace_storage_account a ON a.workspace_id = ws.id
                      LEFT JOIN ws_delta d ON d.id = ws.id
                      LEFT JOIN ws_reserved r ON r.id = ws.id
                    UNION ALL
                    SELECT 'INBOX', ib.id, x.workspace_id, now(),
                           coalesce(s.base_bytes, 0) + coalesce(d.bytes, 0),
                           coalesce(r.bytes, 0),
                           coalesce(s.base_objects, 0) + coalesce(d.objects, 0),
                           coalesce(r.objects, 0)
                      FROM ib
                      LEFT JOIN inbox x ON x.id = ib.id
                      LEFT JOIN inbox_storage s ON s.inbox_id = ib.id
                      LEFT JOIN ib_delta d ON d.id = ib.id
                      LEFT JOIN ib_reserved r ON r.id = ib.id
                    """.trimIndent(),
                ).param("workspaces", scope.workspaceIds.map { it.value })
                .param("inboxes", scope.inboxIds.map { it.value })
                .query { rs, _ ->
                    SnapshotRow(
                        scope = rs.getString("scope"),
                        id = rs.getObject("id", UUID::class.java),
                        owner = rs.getObject("owner", UUID::class.java),
                        t0 = checkNotNull(Timestamps.fromDb(rs, "t0")),
                        usage =
                            StorageUsage(
                                exactLong(rs.getBigDecimal("committed")),
                                exactLong(rs.getBigDecimal("reserved")),
                                // Counts can only drift negative through corruption; a bound cannot carry that.
                                maxOf(0L, exactLong(rs.getBigDecimal("committed_objects"))),
                                maxOf(0L, exactLong(rs.getBigDecimal("reserved_objects"))),
                            ),
                    )
                }.list()
        val global = rows.single { it.scope == "GLOBAL" }
        return StorageUsageSnapshot(
            t0 = global.t0,
            global = global.usage,
            workspaces = rows.filter { it.scope == "WORKSPACE" }.associate { WorkspaceId(it.id!!) to it.usage },
            inboxes =
                rows
                    .filter { it.scope == "INBOX" }
                    .associate { InboxId(it.id!!) to InboxStorageUsage(it.owner?.let(::WorkspaceId), it.usage) },
        )
    }

    /**
     * One multi-row insert. It is bounded by the 50-recipient event cap, and
     * it commits or fails as a whole with T1, so an event's admitted set is
     * never partly reserved.
     */
    private fun insert(reservations: List<StorageReservationDraft>) {
        if (reservations.isEmpty()) return
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
                $values
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
        val scope: String,
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

        /** How much longer than the lock timeout any one T1 statement may run. */
        private val STATEMENT_MARGIN: Duration = Duration.ofSeconds(5)

        /** How long T1 may sit idle between its statements, e.g. a stalled JVM, before PostgreSQL ends it. */
        val IDLE_IN_TRANSACTION: Duration = Duration.ofSeconds(10)

        /**
         * PostgreSQL's `lock_not_available` (lock_timeout), `query_canceled`
         * (statement_timeout) and `idle_in_transaction_session_timeout`.
         */
        private val TIMEOUT_STATES = setOf("55P03", "57014", "25P03")
    }
}
