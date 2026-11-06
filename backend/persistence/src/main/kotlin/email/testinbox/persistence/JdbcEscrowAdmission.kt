package email.testinbox.persistence

import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowAttempt
import email.testinbox.application.port.EscrowInputs
import email.testinbox.application.port.EscrowKeeperState
import email.testinbox.application.port.EscrowLane
import email.testinbox.application.port.EscrowLaneStatus
import email.testinbox.application.port.EscrowPlan
import email.testinbox.application.port.EscrowRefillCause
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.EscrowRow
import email.testinbox.application.port.EscrowScope
import email.testinbox.application.port.EscrowScopeRef
import email.testinbox.application.port.EscrowView
import email.testinbox.application.port.StorageAdmissionUnavailableException
import email.testinbox.application.port.StorageEscrowStore
import email.testinbox.application.port.StorageReservationDraft
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Duration
import java.util.UUID

/**
 * The slot-lane escrow's fast path on PostgreSQL (ADR-038 PROPOSED;
 * structural-admission-design.md §4.2, §7). M1 PROTOTYPE: only the storage
 * benchmark builds it, over a database where V28-proto was applied.
 *
 * It maps and decides nothing: SQLSTATE `TI001` (escrow_short) and a lock
 * statement that waited past `L_w` ([scopeWait]) or was broken as a deadlock
 * become [EscrowAttempt.Escalated]; a timeout anywhere else is mode a's `451`
 * class. [admission] is INJECTED, never constructed here (M3): its escrow strategy
 * is the slow path, and the keeper's refill-only regrant goes through it.
 *
 * Transaction, in three round trips plus COMMIT's: configure (one batch), the lock
 * statement, then `storage_escrow_admit` with the COMMIT batched behind it.
 */
class JdbcEscrowAdmission(
    private val jdbc: JdbcClient,
    private val transactions: TransactionOperations,
    private val admission: JdbcStorageAdmission,
    /** `L_w`: the lock statement's bound (§4.8), 250 ms. Past it the event escalates, never a scope-attributed 451. */
    private val scopeWait: Duration = DEFAULT_SCOPE_WAIT,
    /** Mode a's lock timeout, for the admit statement. */
    private val lockTimeout: Duration = JdbcStorageAdmission.DEFAULT_LOCK_TIMEOUT,
) : StorageEscrowStore {
    private val inputs: EscrowInputs = checkNotNull(admission.escrowInputs) { "the injected T1 store has no escrow strategy" }

    init {
        require(scopeWait.toMillis() in 1 until lockTimeout.toMillis()) { "L_w must be below the lock timeout (B-6)" }
    }

    private val configure = EscrowSql.configure(scopeWait, lockTimeout.plus(STATEMENT_MARGIN), JdbcStorageAdmission.IDLE_IN_TRANSACTION)
    private val admit = EscrowSql.admit(lockTimeout)

    /** A wait on the lock statement that ran past `L_w` (or a deadlock there): escalated, never a 451. */
    private class ScopeWait(
        cause: Throwable,
    ) : RuntimeException(cause)

    override fun <R : Any> tryAdmit(
        scope: EscrowScope,
        decide: (EscrowView) -> EscrowPlan<R>,
    ): EscrowAttempt<R> {
        check(
            !TransactionSynchronizationManager.isActualTransactionActive(),
        ) { "storage admission (T1) must not run inside another transaction" }
        require(scope.inputs == inputs) { "the fast path's inputs must be its slow path's" }
        return try {
            checkNotNull(
                transactions.execute { status ->
                    jdbc.sql(configure).update()
                    val view =
                        try {
                            lock(scope)
                        } catch (failure: RuntimeException) {
                            if (sqlStates(failure).any { it in SCOPE_WAIT_STATES }) throw ScopeWait(failure)
                            throw failure
                        }
                    when (val plan = decide(view)) {
                        is EscrowPlan.Escalate -> {
                            status.setRollbackOnly()
                            EscrowAttempt.Escalated(plan.reason)
                        }

                        is EscrowPlan.Spend -> {
                            spend(scope, plan.reservations)
                            EscrowAttempt.Decided(plan.outcome)
                        }
                    }
                },
            )
        } catch (_: ScopeWait) {
            EscrowAttempt.Escalated(EscalationReason.SCOPE_WAIT)
        } catch (failure: RuntimeException) {
            val states = sqlStates(failure)
            when {
                ESCROW_SHORT in states -> EscrowAttempt.Escalated(EscalationReason.SPEND_MISMATCH)

                states.any { it in TIMEOUT_STATES } -> throw StorageAdmissionUnavailableException(
                    "a fast-path T1 statement exceeded its bound",
                    failure,
                )

                else -> throw failure
            }
        }
    }

    private fun lock(scope: EscrowScope): EscrowView {
        var lane: EscrowLane? = null
        var laneWait = 0L
        var scopeWaitMicros = 0L
        var t0: java.time.Instant? = null
        val workspaces = LinkedHashMap<WorkspaceId, EscrowRow>()
        val inboxes = LinkedHashMap<InboxId, EscrowRow>()
        val owners = LinkedHashMap<InboxId, WorkspaceId?>()
        jdbc
            .sql(EscrowSql.LOCK)
            .param("node", scope.nodeId)
            .param("lane", scope.lane)
            .param("ws", EscrowSql.array("uuid", scope.workspaces.map { it.id }), Types.ARRAY)
            .param("wsp", EscrowSql.array("int8", scope.workspaces.map { it.payload }), Types.ARRAY)
            .param("wsf", EscrowSql.array("int8", scope.workspaces.map { it.footprint }), Types.ARRAY)
            .param("ib", EscrowSql.array("uuid", scope.inboxes.map { it.id }), Types.ARRAY)
            .param("ibp", EscrowSql.array("int8", scope.inboxes.map { it.payload }), Types.ARRAY)
            .param("binding", inputs.binding)
            .param("maxStored", inputs.maxStoredBytes)
            .param("share", inputs.inboxShare.toPlainString())
            .param("block", inputs.model.blockSizeBytes)
            .param("den", inputs.model.fragmentationDenominator)
            .param("omax", inputs.model.objectOverheadMaxBytes)
            .query { rs ->
                t0 = t0 ?: Timestamps.fromDb(rs, "o_t0")
                when (rs.getString("o_kind")) {
                    "L" -> {
                        lane =
                            EscrowLane(
                                status = EscrowLaneStatus.valueOf(rs.getString("o_lane_status").uppercase()),
                                payloadAvail = long(rs, "o_payload_avail"),
                                footprintAvail = long(rs, "o_footprint_avail"),
                            )
                        laneWait = long(rs, "o_refill_us") ?: 0
                        scopeWaitMicros = long(rs, "o_wait_us") ?: 0
                    }

                    "W" -> {
                        workspaces[WorkspaceId(rs.getObject("o_id", UUID::class.java))] = row(rs)
                    }

                    "I" -> {
                        val id = InboxId(rs.getObject("o_id", UUID::class.java))
                        inboxes[id] = row(rs)
                        owners[id] = rs.getObject("o_owner", UUID::class.java)?.let(::WorkspaceId)
                    }
                }
            }
        return EscrowView(
            t0 = checkNotNull(t0) { "the lock statement returned no t0" },
            lane = checkNotNull(lane) { "the lock statement returned no lane row" },
            workspaces = workspaces,
            inboxes = inboxes,
            owners = owners,
            laneWaitMicros = laneWait,
            scopeWaitMicros = scopeWaitMicros,
        )
    }

    private fun row(rs: ResultSet): EscrowRow =
        EscrowRow(
            exact = rs.getBoolean("o_exact"),
            refill = rs.getString("o_refill_cause")?.let { EscrowRefillCause.valueOf(it.uppercase()) },
            refillMicros = long(rs, "o_refill_us"),
            payloadAvail = long(rs, "o_payload_avail"),
            footprintAvail = long(rs, "o_footprint_avail"),
            limitPayload = long(rs, "o_limit_payload"),
            limitFootprint = long(rs, "o_limit_footprint"),
            usedBytes = long(rs, "o_used_bytes"),
            usedObjects = long(rs, "o_used_objects"),
            chargeBytes = long(rs, "o_charge_bytes"),
            chargeObjects = long(rs, "o_charge_objects"),
            active = rs.getBoolean("o_active").takeUnless { rs.wasNull() },
        )

    private fun long(
        rs: ResultSet,
        column: String,
    ): Long? = rs.getLong(column).takeUnless { rs.wasNull() }

    /**
     * `storage_escrow_admit` and the COMMIT in one round trip. With nothing to
     * reserve (every copy refused or inactive) nothing is spent, and the template's
     * commit keeps the inline refills, which are definer-computed truth.
     */
    private fun spend(
        scope: EscrowScope,
        reservations: List<StorageReservationDraft>,
    ) {
        if (reservations.isEmpty()) return
        val first = reservations.first()
        check(
            reservations.all {
                it.bytes == first.bytes && it.createdAt == first.createdAt && it.writeDeadlineAt == first.writeDeadlineAt &&
                    it.nodeId == scope.nodeId && it.generation == first.generation && it.objectKeys.size == first.objectKeys.size
            },
        ) { "every reservation of one event has the same size, objects, t0, deadline, node and generation" }
        jdbc
            .sql(admit)
            .param("node", scope.nodeId)
            .param("lane", scope.lane)
            .param("m", EscrowSql.array("uuid", reservations.map { it.messageId.value }), Types.ARRAY)
            .param("w", EscrowSql.array("uuid", reservations.map { it.workspaceId.value }), Types.ARRAY)
            .param("i", EscrowSql.array("uuid", reservations.map { it.inboxId.value }), Types.ARRAY)
            .param("k", EscrowSql.array("text", reservations.flatMap { it.objectKeys }), Types.ARRAY)
            .param("kpc", first.objectKeys.size)
            .param("bytes", first.bytes)
            .param("created", Timestamps.toDb(first.createdAt))
            .param("deadline", Timestamps.toDb(first.writeDeadlineAt))
            .param("generation", first.generation)
            .param("binding", inputs.binding)
            .param("block", inputs.model.blockSizeBytes)
            .param("den", inputs.model.fragmentationDenominator)
            .param("omax", inputs.model.objectOverheadMaxBytes)
            .update()
    }

    override fun refillGlobal(notReady: (StorageUsageSnapshot) -> String?): EscrowRegrant = admission.escrowRefillOnly(notReady)

    override fun keeperState(
        nodeId: String,
        horizon: Duration,
    ): EscrowKeeperState =
        jdbc
            .sql("SELECT * FROM storage_escrow_keeper_state(:node, make_interval(secs => :h))")
            .param("node", nodeId)
            .param("h", horizon.toMillis() / MILLIS_PER_SECOND)
            .query { rs, _ ->
                EscrowKeeperState(
                    epoch = rs.getLong("o_epoch"),
                    scopeEpoch = rs.getLong("o_scope_epoch"),
                    generation = rs.getLong("o_generation"),
                    globalDue = rs.getBoolean("o_global_due"),
                    reason = rs.getString("o_reason"),
                )
            }.single()

    override fun scopeCandidates(
        lowPayload: Long,
        lowFootprint: Long,
        limit: Int,
    ): List<EscrowScopeRef> =
        jdbc
            .sql("SELECT * FROM storage_escrow_scope_candidates(:binding, :lp, :lf, :limit)")
            .param("binding", inputs.binding)
            .param("lp", lowPayload)
            .param("lf", lowFootprint)
            .param("limit", limit)
            .query { rs, _ ->
                EscrowScopeRef(
                    EscrowScopeRef.Kind.valueOf(rs.getString("o_kind")),
                    rs.getObject("o_id", UUID::class.java),
                    rs.getString("o_cause"),
                )
            }.list()

    override fun refillScope(ref: EscrowScopeRef): Boolean =
        checkNotNull(
            transactions.execute {
                jdbc.sql(configure).update()
                jdbc
                    .sql(
                        "SELECT o_present FROM storage_escrow_refill_scope(CAST(:kind AS char), :id, :binding, :maxStored, " +
                            "CAST(:share AS numeric), :block, :den, :omax)",
                    ).param("kind", ref.kind.name)
                    .param("id", ref.id)
                    .param("binding", inputs.binding)
                    .param("maxStored", inputs.maxStoredBytes)
                    .param("share", inputs.inboxShare.toPlainString())
                    .param("block", inputs.model.blockSizeBytes)
                    .param("den", inputs.model.fragmentationDenominator)
                    .param("omax", inputs.model.objectOverheadMaxBytes)
                    .query(Boolean::class.java)
                    .single()
            },
        )

    private fun sqlStates(failure: Throwable): Set<String> =
        generateSequence(failure) { it.cause }.filterIsInstance<SQLException>().mapNotNull { it.sqlState }.toSet()

    companion object {
        /** `L_w` (§4.8). */
        val DEFAULT_SCOPE_WAIT: Duration = Duration.ofMillis(250)

        /** `escrow_short`: a spend the database refused. */
        const val ESCROW_SHORT = "TI001"

        private val STATEMENT_MARGIN: Duration = Duration.ofSeconds(5)
        private const val MILLIS_PER_SECOND = 1000.0

        /** On the lock statement: lock_timeout (L_w) and a deadlock between scope-row inserts. */
        private val SCOPE_WAIT_STATES = setOf("55P03", "40P01")
        private val TIMEOUT_STATES = setOf("55P03", "57014", "25P03")
    }
}
