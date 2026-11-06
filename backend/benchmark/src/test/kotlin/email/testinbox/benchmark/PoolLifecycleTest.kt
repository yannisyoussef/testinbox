package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/** The scenario pool's connection lifecycle: production's by default, cold, and what the flags say reaches Hikari. */
class PoolLifecycleTest {
    private fun options(vararg flags: String) = BenchmarkOptions.parse(listOf("--jdbc-url", "jdbc:postgresql://db/ti_bench", *flags))

    /** A pool on a target nothing connects to: HikariDataSource opens no connection until one is asked for. */
    private val unreachable = BenchmarkTarget.remote("jdbc:postgresql://127.0.0.1:1/ti_bench", "u", "p", create = false)

    @Test
    fun `by default the pool is cold with production's lifetime and keepalive, HikariCP's defaults`() {
        val lifecycle = PoolLifecycle.of(options())
        lifecycle.coldPool shouldBe true
        lifecycle.maxLifetime shouldBe Duration.ofMinutes(30)
        lifecycle.keepalive shouldBe Duration.ofMinutes(2)
        // The admin pool sets nothing, so it has Hikari's defaults: they ARE the production figures recorded above.
        unreachable.pool(1).use {
            it.maxLifetime shouldBe PoolLifecycle.PRODUCTION_MAX_LIFETIME.toMillis()
            it.keepaliveTime shouldBe PoolLifecycle.PRODUCTION_KEEPALIVE.toMillis()
        }
    }

    @Test
    fun `--pool-max-lifetime reaches the Hikari configuration, with keepalive off when it is not below the lifetime`() {
        val opens = ConnectionOpenCounter()
        unreachable.pool(16, PoolLifecycle.of(options("--pool-max-lifetime", "60s")), opens).use { pool ->
            pool.maxLifetime shouldBe 60_000L
            pool.keepaliveTime shouldBe 0L
            pool.maximumPoolSize shouldBe 16
            pool.metricsTrackerFactory shouldBeSameInstanceAs opens
        }
        unreachable.pool(16, PoolLifecycle.of(options("--pool-max-lifetime", "5m"))).use { pool ->
            pool.maxLifetime shouldBe 300_000L
            pool.keepaliveTime shouldBe 120_000L
        }
    }

    @Test
    fun `durations parse in the documented forms`() {
        PoolLifecycle.parseDuration("60s") shouldBe Duration.ofSeconds(60)
        PoolLifecycle.parseDuration("90") shouldBe Duration.ofSeconds(90)
        PoolLifecycle.parseDuration("30m") shouldBe Duration.ofMinutes(30)
        PoolLifecycle.parseDuration("1h") shouldBe Duration.ofHours(1)
        PoolLifecycle.parseDuration("45000ms") shouldBe Duration.ofMillis(45_000)
        PoolLifecycle.parseDuration("PT30M") shouldBe Duration.ofMinutes(30)
        shouldThrow<UsageException> { PoolLifecycle.parseDuration("sixty") }.message shouldContain "not a duration"
        shouldThrow<UsageException> { PoolLifecycle.parseDuration("PTxS") }.message shouldContain "not a duration"
    }

    @Test
    fun `a cold pool is renewed and never warmed, a warm pool is warmed and never renewed`() {
        val calls = mutableListOf<String>()
        PoolStart.beforeMeasurement(coldPool = true, warm = { calls += "warm" }, renew = { calls += "renew" })
        calls shouldContainExactly listOf("renew")
        calls.clear()
        PoolStart.beforeMeasurement(coldPool = false, warm = { calls += "warm" }, renew = { calls += "renew" })
        calls shouldContainExactly listOf("warm")
    }

    /**
     * Against a real PostgreSQL (Testcontainers, the `--local` target): after the event warm-up
     * every connection has server-prepared a statement; a cold start replaces every one of them
     * with a new backend that has prepared nothing, never calls the warm-up, opens exactly the
     * pool's size, and the running pool uses the configured lifetime.
     */
    @Test
    fun `a cold start replaces every warmed connection with a new backend that has prepared nothing`() {
        BenchmarkTarget.local().use { target ->
            val opens = ConnectionOpenCounter()
            target.pool(POOL, PoolLifecycle.of(options("--pool-max-lifetime", "60s")), opens).use { pool ->
                val warmed =
                    every(pool) { c ->
                        repeat(PoolLifecycle.PREPARE_THRESHOLD + 2) { n ->
                            c.prepareStatement("SELECT ?::int").use { ps ->
                                ps.setInt(1, n)
                                ps.executeQuery().close()
                            }
                        }
                        backend(c) to prepared(c)
                    }
                warmed.map { it.second } shouldBe List(POOL) { 1L }
                val openedBefore = opens.count

                PoolStart.beforeMeasurement(
                    coldPool = true,
                    warm = { error("--cold-pool must never warm the pool") },
                    renew = { PoolStart.renew(pool) },
                )

                (opens.count - openedBefore) shouldBe POOL.toLong()
                val fresh = every(pool) { c -> backend(c) to prepared(c) }
                fresh
                    .map { it.first }
                    .toSet()
                    .intersect(warmed.map { it.first }.toSet())
                    .shouldBeEmpty()
                fresh.map { it.second } shouldBe List(POOL) { 0L }
                pool.hikariConfigMXBean.maxLifetime shouldBe 60_000L
            }
        }
    }

    /** Borrows the pool's every connection at once, so [f] sees each physical connection exactly once. */
    private fun <T> every(
        pool: DataSource,
        f: (Connection) -> T,
    ): List<T> {
        val connections = List(POOL) { pool.connection }
        try {
            return connections.map(f)
        } finally {
            connections.forEach { it.close() }
        }
    }

    /** The backend's identity: its pid and start time (a pid alone could be reused). */
    private fun backend(c: Connection): String =
        single(c, "SELECT pg_backend_pid() || '@' || backend_start FROM pg_stat_activity WHERE pid = pg_backend_pid()")

    private fun prepared(c: Connection): Long = single(c, "SELECT count(*) FROM pg_prepared_statements").toLong()

    private fun single(
        c: Connection,
        sql: String,
    ): String =
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    private companion object {
        const val POOL = 3
    }
}
