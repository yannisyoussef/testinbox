package email.testinbox.benchmark

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import com.zaxxer.hikari.metrics.PoolStats
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * How a scenario's pool starts its measured phase and how long its physical
 * connections live, so T1 can be measured on production's connection lifecycle.
 *
 * Production (the ingestion gateway): `spring.datasource.hikari` sets only
 * `maximum-pool-size` (`TESTINBOX_DB_POOL_SIZE`, default 8,
 * `backend/ingestion/src/main/resources/application-deployed.yaml`) and the
 * `ApplicationName` data-source property (`application.yaml`); neither the
 * production document nor any committed deployment file sets a lifetime. So the
 * lifetime and keepalive are HikariCP's own defaults ([PRODUCTION_SOURCE]):
 * 30 min and 2 min. Hikari retires each connection at a random point in the last
 * quarter of its lifetime (75 % – 100 %), and replaces it with a new physical
 * connection, which has no server-prepared statement and no cached plan.
 *
 * - [coldPool] (the default): no warm-up of the pool's connections. After the
 *   untimed event warm-up (JIT, slots, the T1 probe) every connection is evicted
 *   and the pool is refilled with new physical connections that have executed
 *   nothing, as on an ingestion node that has just been deployed. Each then plans
 *   T1's snapshot inside the lock on its first [PREPARE_THRESHOLD] executions.
 * - warm (`--warm-pool`): every connection executes T1's snapshot past pgJDBC's
 *   prepare threshold before the phase ([PoolStart.warm]); not production's
 *   lifecycle, so never ADR evidence ([AdrConformance]).
 */
data class PoolLifecycle(
    val coldPool: Boolean,
    val maxLifetime: Duration,
    /** Hikari's keepalive: production's 2 min, or 0 (disabled) when that is not below [maxLifetime], as Hikari would do itself. */
    val keepalive: Duration,
) {
    /** Applies the lifetime and keepalive to a pool's configuration, before the pool starts. */
    fun applyTo(config: HikariConfig) {
        config.maxLifetime = maxLifetime.toMillis()
        config.keepaliveTime = keepalive.toMillis()
    }

    companion object {
        /** HikariCP's default `maxLifetime`; the ingestion gateway does not override it ([PRODUCTION_SOURCE]). */
        val PRODUCTION_MAX_LIFETIME: Duration = Duration.ofMinutes(30)

        /** HikariCP's default `keepaliveTime` (since 5.0); not overridden by the ingestion gateway either. */
        val PRODUCTION_KEEPALIVE: Duration = Duration.ofMinutes(2)

        /**
         * HikariCP refuses a `maxLifetime` below 30 s: it logs a warning and silently uses its
         * 30 min default instead (`HikariConfig.validateNumerics`). The harness refuses it at
         * parse time, so a short lifetime can never quietly become the long one.
         */
        val HIKARI_MIN_MAX_LIFETIME: Duration = Duration.ofSeconds(30)

        /**
         * pgJDBC's default `prepareThreshold`: the first four executions of a text on a
         * connection are unnamed (parsed and planned each time), the fifth server-prepares it
         * (planned once more, then cached). No TestInbox configuration sets it.
         */
        const val PREPARE_THRESHOLD = 5

        const val PRODUCTION_SOURCE =
            "HikariCP 7.0.2 defaults (maxLifetime 30 min, keepaliveTime 2 min): the ingestion gateway's " +
                "spring.datasource.hikari sets only maximum-pool-size (application-deployed.yaml, TESTINBOX_DB_POOL_SIZE, " +
                "default 8) and data-source-properties.ApplicationName (application.yaml); no committed deployment file " +
                "sets a lifetime"

        fun of(options: BenchmarkOptions): PoolLifecycle {
            val lifetime = options.effectivePoolMaxLifetime
            return PoolLifecycle(
                coldPool = options.coldPool,
                maxLifetime = lifetime,
                keepalive = if (PRODUCTION_KEEPALIVE < lifetime) PRODUCTION_KEEPALIVE else Duration.ZERO,
            )
        }

        /** `60s`, `90s`, `30m`, `1h`, `45000ms`, a bare number of seconds, or ISO-8601 (`PT30M`). */
        fun parseDuration(text: String): Duration {
            val trimmed = text.trim()
            if (trimmed.startsWith("P", ignoreCase = true)) {
                return try {
                    Duration.parse(trimmed)
                } catch (_: DateTimeParseException) {
                    throw UsageException("'$text' is not a duration (e.g. 60s, 30m, PT30M)")
                }
            }
            val match = DURATION.matchEntire(trimmed) ?: throw UsageException("'$text' is not a duration (e.g. 60s, 30m, PT30M)")
            val amount = match.groupValues[1].toLong()
            return when (match.groupValues[2]) {
                "ms" -> Duration.ofMillis(amount)
                "", "s" -> Duration.ofSeconds(amount)
                "m" -> Duration.ofMinutes(amount)
                else -> Duration.ofHours(amount)
            }
        }

        private val DURATION = Regex("^(\\d{1,9})(ms|s|m|h|)$")
    }
}

/**
 * Hikari's own connection-created hook (the one Micrometer's
 * `hikaricp_connections_creation` timer reads): every physical connection the pool
 * opens, initial fill, eviction refill and lifetime retirement alike, EXCEPT the pool's
 * very first one, which Hikari opens for its fail-fast check before it installs the
 * tracker (`HikariPool`'s constructor). So a count from the pool's creation is one low;
 * a difference between two readings (the measured phase's) is exact.
 */
class ConnectionOpenCounter : MetricsTrackerFactory {
    private val opened = AtomicLong()

    val count: Long get() = opened.get()

    override fun create(
        poolName: String,
        poolStats: PoolStats,
    ): IMetricsTracker =
        object : IMetricsTracker {
            override fun recordConnectionCreatedMillis(connectionCreatedMillis: Long) {
                opened.incrementAndGet()
            }
        }
}

/** What the scenario's pool did around its measured phase (`pool` in each scenario's evidence). */
data class PoolPhase(
    val coldPool: Boolean,
    val maxLifetimeMs: Long,
    val keepaliveMs: Long,
    val poolSize: Int,
    /**
     * Physical connections opened from the pool's creation to the start of the measured phase (probe, warm-up,
     * `--cold-pool`'s refill), less Hikari's first, fail-fast connection ([ConnectionOpenCounter]).
     */
    val connectionsOpenedBeforePhase: Long,
    /** Physical connections opened during the measured phase: lifetime retirements (and any refill still running). */
    val connectionsOpenedInPhase: Long,
)

/**
 * One scenario's pool: Hikari with the run's [lifecycle] and an open counter, the step-timing
 * proxy over it when asked (`--t1-steps` / `--focused`: T1's statements observed, never changed,
 * and each snapshot execution counted on its physical connection), and the measured phase's
 * connection bookkeeping ([PoolPhase]).
 */
class ScenarioPool(
    target: BenchmarkTarget,
    size: Int,
    private val lifecycle: PoolLifecycle,
    stepTiming: Boolean,
) : AutoCloseable {
    private val opens = ConnectionOpenCounter()
    private val hikari: HikariDataSource = target.pool(size, lifecycle, opens)
    private var openedBeforePhase = 0L

    val ledger: ConnectionLedger? = if (stepTiming) ConnectionLedger() else null

    /** What the protocol uses: the step-timing proxy over the pool, or the pool itself. */
    val dataSource: DataSource = ledger?.let { HoldTiming.wrap(hikari, it) } ?: hikari

    /** After the event warm-up: a cold pool is renewed, a warm one warmed with [statements] scoped to [scope] ([PoolStart]). */
    fun beforeMeasurement(
        statements: List<String>,
        scope: SeededInbox,
    ) = PoolStart.beforeMeasurement(
        lifecycle.coldPool,
        warm = { PoolStart.warm(hikari, dataSource, statements, scope) },
        renew = { PoolStart.renew(hikari) },
    )

    fun startPhase() {
        ledger?.startPhase()
        openedBeforePhase = opens.count
    }

    /** The pool as the phase ends; Hikari's own figures, so a value Hikari corrected is the one recorded. */
    fun phase(): PoolPhase =
        PoolPhase(
            coldPool = lifecycle.coldPool,
            maxLifetimeMs = hikari.maxLifetime,
            keepaliveMs = hikari.keepaliveTime,
            poolSize = hikari.maximumPoolSize,
            connectionsOpenedBeforePhase = openedBeforePhase,
            connectionsOpenedInPhase = opens.count - openedBeforePhase,
        )

    /** T1's in-lock planning over the phase: the ledger's counts (step timing only), the phase's completed [events], the server's [statement]. */
    fun planning(
        events: List<TimedEvent>,
        statement: StatementStats?,
    ): T1Planning? = T1Planning.of(ledger?.phase(), events, statement)

    override fun close() = hikari.close()
}

/** The one decision between the event warm-up and the measured phase. */
object PoolStart {
    /** A cold pool is renewed and NEVER warmed; a warm pool is warmed and never renewed. */
    fun beforeMeasurement(
        coldPool: Boolean,
        warm: () -> Unit,
        renew: () -> Unit,
    ) {
        if (coldPool) renew() else warm()
    }

    /**
     * `--warm-pool` only (not ADR evidence): every connection of [pool], untimed, executes each
     * of T1's [statements] (named-parameter text, as the store holds it) past pgJDBC's
     * `prepareThreshold` under `force_generic_plan`, so each holds the server-prepared statement
     * and its generic plan. That removes the in-lock planning a new connection does (12–16 ms a
     * plan on the staging host, 164–296 plans a minute at c100 in the 80482e6 diagnostic run),
     * which production pays after every deploy and every `maxLifetime` (the review of
     * 2026-11-06). Read-only, and rolled back. Borrowed through [observed] (the step-timing proxy
     * when there is one), so the [ConnectionLedger] counts these executions as pgJDBC does.
     */
    fun warm(
        pool: HikariDataSource,
        observed: DataSource,
        statements: List<String>,
        scope: SeededInbox,
    ) {
        val texts = statements.map { NamedParameterUtils.parseSqlStatementIntoString(it) }
        val connections = List(pool.maximumPoolSize) { observed.connection }
        try {
            for (c in connections) {
                c.autoCommit = false
                try {
                    c.createStatement().use { it.execute("SET LOCAL plan_cache_mode = force_generic_plan") }
                    for (text in texts) {
                        repeat(WARM_EXECUTIONS) {
                            c.prepareStatement(text).use { ps ->
                                ps.setObject(1, c.createArrayOf("uuid", arrayOf(scope.workspaceId)), java.sql.Types.ARRAY)
                                ps.setObject(2, c.createArrayOf("uuid", arrayOf(scope.inboxId)), java.sql.Types.ARRAY)
                                ps.executeQuery().close()
                            }
                        }
                    }
                } finally {
                    c.rollback()
                    c.autoCommit = true
                }
            }
        } finally {
            connections.forEach { it.close() }
        }
    }

    /** pgJDBC's prepare threshold server-prepares on the 5th use; two more run the generic plan. */
    private const val WARM_EXECUTIONS = PoolLifecycle.PREPARE_THRESHOLD + 2

    /**
     * Every connection of [pool] is replaced by a new physical connection that has executed
     * nothing: the current ones are soft-evicted (an idle one is closed now, a borrowed one when
     * it is returned, and none is handed out again), then the pool's full size is borrowed at
     * once, which makes Hikari open that many new connections, and returned. Opening them here,
     * untimed, keeps connection establishment out of the measured phase, as on a node whose pool
     * has filled before traffic reaches it; their first T1s still plan inside the lock.
     */
    fun renew(pool: HikariDataSource) {
        checkNotNull(pool.hikariPoolMXBean) { "the scenario pool has not started" }.softEvictConnections()
        val fresh = ArrayList<java.sql.Connection>(pool.maximumPoolSize)
        try {
            repeat(pool.maximumPoolSize) { fresh += pool.connection }
        } finally {
            fresh.forEach { it.close() }
        }
    }
}
