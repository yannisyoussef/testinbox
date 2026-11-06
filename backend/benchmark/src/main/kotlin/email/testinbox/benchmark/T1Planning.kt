package email.testinbox.benchmark

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Every execution of T1's snapshot statement through the scenario's pool, per PHYSICAL
 * connection and statement text (`--t1-steps` / `--focused`, [HoldTiming]). pgJDBC counts
 * its prepare threshold per connection and per text, so the ordinal returned here is
 * pgJDBC's own: executions 1 to [threshold] − 1 are unnamed (parsed and planned at Bind
 * each time), execution [threshold] server-prepares the text (planned once more, then
 * cached); later ones reuse the cached generic plan unless an invalidation (an ANALYZE,
 * autovacuum's included, or DDL on a table it reads) makes PostgreSQL plan it again.
 * Every snapshot runs after the admission lock is granted, so each of those plans is
 * made inside the lock.
 *
 * Counted on every execution, whether or not an event is on the thread, so the probe,
 * the event warm-up and `--warm-pool`'s warm-up advance a connection's ordinal exactly
 * as they advance pgJDBC's count.
 */
class ConnectionLedger(
    val threshold: Int = PoolLifecycle.PREPARE_THRESHOLD,
) {
    private val executions = IdentityHashMap<Any, MutableMap<String, Int>>()
    private val phaseConnections: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
    private var phaseExecutions = 0L
    private var phaseCold = 0L
    private var phaseFirstUses = 0

    /** Counts one execution of [sql] on the physical connection [physical]; returns its 1-based ordinal there. */
    @Synchronized
    fun executed(
        physical: Any,
        sql: String,
    ): Int {
        val perText = executions.getOrPut(physical) { HashMap() }
        val ordinal = (perText[sql] ?: 0) + 1
        perText[sql] = ordinal
        phaseExecutions++
        if (ordinal <= threshold) phaseCold++
        if (ordinal == 1) phaseFirstUses++
        phaseConnections += physical
        return ordinal
    }

    /** The measured phase starts: the phase's counters restart, every connection's ordinal is kept. */
    @Synchronized
    fun startPhase() {
        phaseExecutions = 0
        phaseCold = 0
        phaseFirstUses = 0
        phaseConnections.clear()
    }

    @Synchronized
    fun phase(): LedgerPhase = LedgerPhase(phaseExecutions, phaseCold, phaseConnections.size, phaseFirstUses)
}

/** The ledger over one measured phase. */
data class LedgerPhase(
    /** Snapshot executions in the phase, every event and every T1 outcome. */
    val snapshotExecutions: Long,
    /** Of those, executions 1 to the prepare threshold on their connection: one in-lock plan each. */
    val coldExecutions: Long,
    /** Physical connections that executed the snapshot in the phase. */
    val physicalConnections: Int,
    /** Connections (per statement text) whose FIRST snapshot execution was in the phase. */
    val firstUsedInPhase: Int,
)

/**
 * In-lock planning of T1's snapshot over one measured phase (`t1Planning` in the evidence,
 * FOCUSED.md). Never a §11 criterion. Three sources, labelled apart:
 *
 * - **client** (`--t1-steps` / `--focused` only, [ConnectionLedger]): how many snapshot
 *   executions were a connection's first [prepareThreshold] (a cold-connection plan each,
 *   exactly, by pgJDBC's and PostgreSQL's rules), and per completed T1 whether its snapshot
 *   was one of them: T1 (completion − due) and the snapshot's round trip, cold versus warm.
 * - **server** (`pg_stat_statements`, when preloaded with `track_planning`): plans and plan
 *   time of the snapshot statement over the phase, both kinds together.
 * - **derived**: invalidation re-plans = server plans − client cold plans; the cold plan's
 *   in-lock cost ≈ mean cold snapshot − mean warm snapshot (it also carries a new backend's
 *   catalog-cache loading, which is the same in-lock cost).
 *
 * Limits: a re-plan cannot be attributed to a T1 client side (the extended protocol's Parse,
 * Bind and Execute share one round trip and the server reports plans only in aggregate), so
 * the server's mean plan time is per kind only when the other kind is 0. Only the snapshot
 * is counted; the reservation insert also plans on a cold connection, cheaply.
 */
data class T1Planning(
    val prepareThreshold: Int,
    val client: Client?,
    val server: Server?,
) {
    data class Client(
        val snapshotExecutions: Long,
        val coldSnapshotExecutions: Long,
        val physicalConnections: Int,
        val firstUsedInPhase: Int,
        /** Completed T1s whose snapshot was among its connection's first [prepareThreshold] executions. */
        val coldT1: LatencySummary?,
        val warmT1: LatencySummary?,
        val coldSnapshotMeanMs: Double?,
        val warmSnapshotMeanMs: Double?,
    )

    data class Server(
        val plans: Long,
        val totalPlanMs: Double,
        val meanPlanMs: Double,
    )

    /** server plans − client cold plans: the re-plans an invalidation forced; null without both sources or with no plan counted. */
    val invalidationReplans: Long?
        get() =
            if (client == null || server == null || server.plans == 0L) {
                null
            } else {
                maxOf(0L, server.plans - client.coldSnapshotExecutions)
            }

    /** Mean cold snapshot − mean warm snapshot, ms: a cold plan's in-lock cost [derived]; null without both kinds. */
    val coldPlanCostMs: Double?
        get() {
            val cold = client?.coldSnapshotMeanMs ?: return null
            val warm = client.warmSnapshotMeanMs ?: return null
            return cold - warm
        }

    companion object {
        /**
         * [ledger] is null without step timing; [events] are the phase's completed events; [statement]
         * is `pg_stat_statements` for the snapshot (null when unavailable, plans 0 without `track_planning`).
         */
        fun of(
            ledger: LedgerPhase?,
            events: List<TimedEvent>,
            statement: StatementStats?,
            threshold: Int = PoolLifecycle.PREPARE_THRESHOLD,
        ): T1Planning? {
            val server = statement?.let { Server(it.plans, it.totalPlanMs, it.meanPlanMs) }
            val client =
                ledger?.let { phase ->
                    val observed = events.filter { it.timings.t1EndNanos != 0L && it.timings.steps.snapshotOrdinal > 0 }
                    val (cold, warm) = observed.partition { it.timings.steps.snapshotOrdinal <= threshold }
                    Client(
                        snapshotExecutions = phase.snapshotExecutions,
                        coldSnapshotExecutions = phase.coldExecutions,
                        physicalConnections = phase.physicalConnections,
                        firstUsedInPhase = phase.firstUsedInPhase,
                        coldT1 = LatencySummary.ofNanos(cold.map { it.timings.t1EndNanos - it.dueNanos }),
                        warmT1 = LatencySummary.ofNanos(warm.map { it.timings.t1EndNanos - it.dueNanos }),
                        coldSnapshotMeanMs = snapshotMeanMs(cold),
                        warmSnapshotMeanMs = snapshotMeanMs(warm),
                    )
                }
            if (client == null && server == null) return null
            return T1Planning(threshold, client, server)
        }

        private fun snapshotMeanMs(events: List<TimedEvent>): Double? =
            events
                .filter { it.timings.steps.snapshotEnd != 0L }
                .map { (it.timings.steps.snapshotEnd - it.timings.steps.snapshotStart) / NANOS_PER_MILLI }
                .takeIf { it.isNotEmpty() }
                ?.average()

        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
