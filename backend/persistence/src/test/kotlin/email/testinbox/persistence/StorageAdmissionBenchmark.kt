package email.testinbox.persistence

import com.zaxxer.hikari.HikariDataSource
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageEnforcement
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ADR-035 T1 local sanity benchmark (TI-STORAGE-002 §39), on the REAL
 * adapter and use case.
 *
 * This is NOT the §11 staging enablement gate, and laptop numbers are not
 * production evidence. It exists to catch a pathology: a T1 that grows with
 * its recipient count (N queries per recipient), or one that collapses
 * under a live reservation backlog.
 *
 * Run: `docs/adr/0035-benchmark/t1-admission/run.sh`, or
 * `./gradlew :persistence:test -PstorageBenchmark --tests '*StorageAdmissionBenchmark*'`.
 */
class StorageAdmissionBenchmark : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient

    private data class Scenario(
        val name: String,
        val candidates: Int,
        val workspaces: Int,
        val backlog: Int,
        val clients: Int = 1,
    )

    private val scenarios =
        listOf(
            Scenario("1 recipient, 1 workspace", 1, 1, 0),
            Scenario("10 recipients, 1 workspace", 10, 1, 0),
            Scenario("50 recipients, 1 workspace", 50, 1, 0),
            Scenario("1 recipient, 200 workspaces", 1, 200, 0),
            Scenario("10 recipients, 200 workspaces", 10, 200, 0),
            Scenario("50 recipients, 200 workspaces", 50, 200, 0),
            Scenario("1 recipient, 200 ws, 1 000 live reservations", 1, 200, 1_000),
            Scenario("10 recipients, 200 ws, 1 000 live reservations", 10, 200, 1_000),
            Scenario("50 recipients, 200 ws, 1 000 live reservations", 50, 200, 1_000),
            Scenario("10 recipients, 200 ws, 1 000 reservations, 16 clients", 10, 200, 1_000, clients = 16),
            // ADR-035 §11's global sum reads every workspace's base row under the lock.
            Scenario("1 recipient, 10 000 ws, 1 000 live reservations", 1, 10_000, 1_000),
            Scenario("10 recipients, 10 000 ws, 1 000 live reservations", 10, 10_000, 1_000),
            Scenario("50 recipients, 10 000 ws, 1 000 live reservations", 50, 10_000, 1_000),
        )

    @Test
    fun `T1 sanity benchmark`() {
        val rows = scenarios.map(::run)
        println("\n| Scenario | T1 p50 (ms) | p95 | p99 | T1/s |")
        println("|---|---:|---:|---:|---:|")
        rows.forEach { println(it) }
    }

    private fun run(scenario: Scenario): String {
        val fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
        val db = fx.db
        val random = Random(35)
        // Seeded in bulk: 10 000 workspaces one statement at a time would
        // measure the seeding, not T1.
        val workspaces =
            db.jdbc
                .sql(
                    """
                    WITH w AS (INSERT INTO workspace (id, name, created_at)
                               SELECT gen_random_uuid(), 'w', now() FROM generate_series(1, :n) RETURNING id)
                    INSERT INTO project (id, workspace_id, name, created_at) SELECT id, id, 'p', now() FROM w RETURNING id
                    """.trimIndent(),
                ).param("n", scenario.workspaces)
                .query(UUID::class.java)
                .list()
                .filterNotNull()
        val inboxes =
            db.jdbc
                .sql(
                    """
                    INSERT INTO inbox (id, workspace_id, project_id, address, address_mode, state, created_at, expires_at)
                    SELECT gen_random_uuid(), w, w, gen_random_uuid() || '@bench.test', 'GENERATED', 'ACTIVE', now(), now() + interval '1 day'
                      FROM unnest(ARRAY[:ws]::uuid[]) AS w, generate_series(1, :perWorkspace)
                    RETURNING workspace_id, id
                    """.trimIndent(),
                ).param("ws", workspaces)
                .param("perWorkspace", maxOf(5, scenario.candidates))
                .query { rs, _ -> rs.getObject(1, UUID::class.java)!! to rs.getObject(2, UUID::class.java)!! }
                .list()
                .groupBy({ it.first }, { it.second })
        // A standing ledger: every inbox and workspace has a base; one
        // compaction interval's worth of unfolded deltas; and the live
        // reservation backlog.
        db.jdbc
            .sql(
                """
                WITH i AS (INSERT INTO inbox_storage (inbox_id, workspace_id, base_bytes)
                           SELECT id, workspace_id, 1000 + (random() * 1000000)::bigint FROM inbox RETURNING workspace_id, base_bytes)
                INSERT INTO workspace_storage_account (workspace_id, base_bytes)
                SELECT workspace_id, sum(base_bytes) FROM i GROUP BY workspace_id
                """.trimIndent(),
            ).update()
        repeat(500) {
            workspaces.random(random).let { ws ->
                fx.delta(ws, inboxes.getValue(ws).random(random), random.nextLong(1, 100_000))
            }
        }
        repeat(scenario.backlog) { i ->
            workspaces.random(random).let { ws ->
                fx.reservation(ws, inboxes.getValue(ws).random(random), 10_000, state = if (i % 5 == 0) "RELEASING" else "RESERVED")
            }
        }
        db.jdbc.sql("ANALYZE").update()
        // Pooled, as in production (Hikari). An unpooled data source would open
        // a physical connection per T1 and measure connection setup instead.
        val pool =
            HikariDataSource().apply {
                jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/" + db.name
                username = postgres.username
                password = postgres.password
                maximumPoolSize = scenario.clients + 1
            }
        val pooledJdbc = JdbcClient.create(pool)
        val admission =
            StorageAdmission(
                JdbcStorageAdmission(pooledJdbc, TransactionTemplate(DataSourceTransactionManager(pool))),
                StorageCapacityPolicyFixtures.GENEROUS,
                StorageEnforcement.ALL,
            )

        val allInboxes = inboxes.flatMap { (ws, ibs) -> ibs.map { ws to it } }

        fun event(random: Random) =
            fx.request(
                26_000,
                // Distinct inboxes: an event carries at most one copy per inbox.
                allInboxes.shuffled(random).take(scenario.candidates).map { (ws, inbox) -> fx.candidate(ws, inbox, attachments = 1) },
            )

        // Warm up, then measure. Every event's own reservations are removed
        // after it, untimed, so the backlog stays at the scenario's size.
        repeat(WARMUP) { admission.admit(event(random)) }
        pooledJdbc.sql("DELETE FROM storage_reservation WHERE node_id = :n").param("n", AdmissionFixture.NODE).update()
        val latencies = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val clients = Executors.newFixedThreadPool(scenario.clients)
        val start = CountDownLatch(1)
        val perClient = ITERATIONS / scenario.clients
        val workers =
            List(scenario.clients) { client ->
                clients.submit {
                    val r = Random(client.toLong())
                    start.await()
                    repeat(perClient) {
                        val request = event(r)
                        val t = System.nanoTime()
                        val result = admission.admit(request)
                        latencies += System.nanoTime() - t
                        result.admitted.size shouldBe scenario.candidates
                        pooledJdbc
                            .sql("DELETE FROM storage_reservation WHERE message_id IN (:ids)")
                            .param("ids", request.candidates.map { it.messageId.value })
                            .update()
                    }
                }
            }
        val began = System.nanoTime()
        try {
            start.countDown()
            clients.shutdown()
            clients.awaitTermination(10, TimeUnit.MINUTES) shouldBe true
            workers.forEach { it.get() } // a failed worker fails the benchmark, never just shrinks the sample
        } finally {
            clients.shutdownNow()
            pool.close()
        }
        val elapsedSeconds = (System.nanoTime() - began) / 1e9
        val sorted = latencies.sorted()

        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1e6
        return "| ${scenario.name} | %.2f | %.2f | %.2f | %.0f |".format(pct(0.50), pct(0.95), pct(0.99), sorted.size / elapsedSeconds)
    }

    private companion object {
        const val WARMUP = 100
        const val ITERATIONS = 800
    }
}
