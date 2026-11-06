package email.testinbox.benchmark

import com.zaxxer.hikari.HikariDataSource
import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.persistence.JdbcStorageAdmission
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * `--pool-size N`: the scenario pool Hikari runs is N connections with production's 30 s
 * `connectionTimeout`, and a T1 that waits for a pool connection carries that wait in its
 * completion − due, as production's would (Testcontainers, the `--local` target).
 */
class PoolSizeTest {
    private val lifecycle = PoolLifecycle.of(BenchmarkOptions(local = true))

    @Test
    fun `the scenario pool size is --pool-size when given, c + 6 otherwise`() {
        val spec = ScenarioSpec(AdmissionMode.CHOSEN, concurrency = 100, offeredRate = 31.0)
        ScenarioRunner.poolSize(BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--pool-size", "8")), spec) shouldBe 8
        ScenarioRunner.poolSize(BenchmarkOptions.parse(listOf("--local", "--focused-cohort")), spec) shouldBe 106
        ScenarioRunner.poolSize(BenchmarkOptions.parse(listOf("--local")), spec) shouldBe 106
    }

    @Test
    fun `with N = 2 a T1 that finds every connection borrowed waits, succeeds, and its wait is inside T1`() {
        BenchmarkTarget.local().use { target ->
            target.pool(2).use { admin -> target.migrate(admin) }
            val seeded = target.pool(2).use { admin -> Seeder(JdbcClient.create(admin), Random(1)).population(1, 1).inboxes.single() }
            ScenarioPool(target, 2, lifecycle, stepTiming = false).use { scenarioPool ->
                val hikari = scenarioPool.dataSource as HikariDataSource
                // The size and timeout reach Hikari.
                hikari.maximumPoolSize shouldBe 2
                scenarioPool.phase().poolSize shouldBe 2
                hikari.connectionTimeout shouldBe 30_000L

                val store =
                    TimingAdmissionStore(
                        JdbcStorageAdmission(
                            JdbcClient.create(hikari),
                            TransactionTemplate(DataSourceTransactionManager(hikari)),
                            readsFootprint = false,
                        ),
                    )
                val scope = StorageAdmissionScope(setOf(WorkspaceId(seeded.workspaceId)), setOf(InboxId(seeded.inboxId)))
                val timings = EventTimings()
                val outcome = AtomicReference<Result<String>>()
                // Both connections are borrowed: the next client must wait for one, as an SMTP session does on a node.
                val held = List(2) { hikari.connection }
                val due = System.nanoTime()
                val worker =
                    Thread {
                        EventTimings.current.set(timings)
                        outcome.set(runCatching { store.admit(scope) { StorageAdmissionPlan(emptyList(), "admitted") } })
                    }.apply { start() }
                // Deterministic: proceed only once Hikari reports the worker waiting for a connection (no sleeps).
                val deadline = System.nanoTime() + WAIT_BOUND_NANOS
                while (hikari.hikariPoolMXBean.threadsAwaitingConnection < 1) {
                    check(worker.isAlive && System.nanoTime() < deadline) { "the worker never waited for a connection" }
                    Thread.onSpinWait()
                }
                val releasedAt = System.nanoTime()
                held.first().close()
                worker.join()
                held.last().close()

                // Nobody errors: the waiting client is served when a connection returns.
                outcome.get().getOrThrow() shouldBe "admitted"
                // T1 began before the release and ended after it: the pool wait is inside T1 and inside completion − due.
                (timings.t1EndNanos - timings.t1Nanos < releasedAt) shouldBe true
                (timings.t1EndNanos > releasedAt) shouldBe true
                (timings.t1EndNanos - due > releasedAt - due) shouldBe true
            }
        }
    }

    private companion object {
        val WAIT_BOUND_NANOS =
            java.util.concurrent.TimeUnit.SECONDS
                .toNanos(20)
    }
}
