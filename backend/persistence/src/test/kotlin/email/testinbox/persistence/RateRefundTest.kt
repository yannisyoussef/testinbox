package email.testinbox.persistence

import email.testinbox.application.port.RateCharge
import email.testinbox.application.port.RateLimiter
import email.testinbox.domain.InboxId
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `JdbcRateLimiter.refund` against a real PostgreSQL (owner decision
 * 2026-11-06, ADR-027 §4 as amended): exactly what was taken comes back,
 * never above capacity, all or nothing, under the spend's own row lock, and
 * never into a bucket that no longer exists.
 */
class RateRefundTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var inboxes: JdbcInboxRepository

    /** Refill so slow that it is invisible over a test: what the bucket holds is what was taken and given back. */
    private val still = RatePolicy(capacity = 3, refillPerSecond = 1e-6)

    private fun limiter(
        workspace: RatePolicy = still,
        inbox: RatePolicy = still,
    ): RateLimiter = JdbcRateLimiter(jdbc, transactionManager) { _, perInbox -> if (perInbox) inbox else workspace }

    /** A separate pool and transaction manager: a genuinely separate node. */
    private fun independentNode(policy: RatePolicy): RateLimiter {
        val dataSource =
            org.springframework.jdbc.datasource
                .DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .apply { setDriverClassName("org.postgresql.Driver") }
        return JdbcRateLimiter(
            JdbcClient.create(dataSource),
            org.springframework.jdbc.support
                .JdbcTransactionManager(dataSource),
        ) { _, _ -> policy }
    }

    private fun tokens(
        workspace: WorkspaceId,
        inbox: InboxId? = null,
    ): Double? =
        jdbc
            .sql(
                """
                SELECT tokens FROM rate_bucket
                 WHERE workspace_id = :ws AND category = 'INGEST' AND inbox_id IS NOT DISTINCT FROM :inbox
                """.trimIndent(),
            ).param("ws", workspace.value)
            .param("inbox", inbox?.value)
            .query { rs, _ -> rs.getDouble(1) }
            .optional()
            .orElse(null)

    private fun dbNow(): Instant = jdbc.sql("SELECT now()").query { rs, _ -> Timestamps.fromDb(rs, "now")!! }.single()

    @Test
    fun `a refund gives back exactly the tokens taken, per bucket`() {
        val (ws, project) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(ws, project).also { inboxes.insert(it) }
        val limiter = limiter()
        repeat(2) {
            limiter.tryConsume(ws, RateCategory.INGEST, inbox.id).allowed shouldBe true
            limiter.tryConsume(ws, RateCategory.INGEST).allowed shouldBe true
        }
        tokens(ws, inbox.id)!! shouldBe (1.0 plusOrMinus 1e-3)

        // One copy's two charges back; the other copy's stand.
        limiter.refund(listOf(RateCharge(ws, RateCategory.INGEST, inbox.id), RateCharge(ws, RateCategory.INGEST)))

        tokens(ws, inbox.id)!! shouldBe (2.0 plusOrMinus 1e-3)
        tokens(ws)!! shouldBe (2.0 plusOrMinus 1e-3)
    }

    @Test
    fun `several charges of one bucket come back together, and never above capacity`() {
        val (ws, _) = Fixtures.provisionTenant(jdbc)
        val limiter = limiter()
        repeat(3) { limiter.tryConsume(ws, RateCategory.INGEST).allowed shouldBe true }
        tokens(ws)!! shouldBe (0.0 plusOrMinus 1e-3)

        limiter.refund(List(3) { RateCharge(ws, RateCategory.INGEST) })
        tokens(ws)!! shouldBe (3.0 plusOrMinus 1e-3)

        // A refund into a full bucket is absorbed by the clamp: capacity, never more.
        limiter.refund(List(5) { RateCharge(ws, RateCategory.INGEST) })
        tokens(ws) shouldBe 3.0
    }

    @Test
    fun `a refund never creates a bucket - a deleted inbox has nothing to give back to`() {
        val (ws, project) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(ws, project).also { inboxes.insert(it) }
        val limiter = limiter()
        limiter.tryConsume(ws, RateCategory.INGEST, inbox.id).allowed shouldBe true
        jdbc.sql("DELETE FROM inbox WHERE id = ?").param(inbox.id.value).update() // cascades to its bucket

        limiter.refund(listOf(RateCharge(ws, RateCategory.INGEST, inbox.id), RateCharge(ws, RateCategory.INGEST)))

        tokens(ws, inbox.id) shouldBe null
        tokens(ws) shouldBe null // never charged, so never created
    }

    @Test
    fun `a refund that cannot commit applies none of its buckets`() {
        val (ws, project) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(ws, project).also { inboxes.insert(it) }
        val limiter = limiter()
        limiter.tryConsume(ws, RateCategory.INGEST, inbox.id).allowed shouldBe true
        limiter.tryConsume(ws, RateCategory.INGEST).allowed shouldBe true
        // The inbox bucket's update fails; the workspace bucket's (sorted first) must roll back with it.
        val guard = "refund_fails_${ws.value.toString().take(8)}"
        jdbc
            .sql(
                "ALTER TABLE rate_bucket ADD CONSTRAINT $guard CHECK (inbox_id IS DISTINCT FROM '${inbox.id.value}' OR tokens < 2.5) NOT VALID",
            ).update()
        try {
            runCatching {
                limiter.refund(listOf(RateCharge(ws, RateCategory.INGEST), RateCharge(ws, RateCategory.INGEST, inbox.id)))
            }.isFailure shouldBe true
        } finally {
            jdbc.sql("ALTER TABLE rate_bucket DROP CONSTRAINT $guard").update()
        }

        tokens(ws)!! shouldBe (2.0 plusOrMinus 1e-3)
        tokens(ws, inbox.id)!! shouldBe (2.0 plusOrMinus 1e-3)
    }

    @Test
    fun `owner test 2 (limiter) - repeated deferrals and concurrent refunds never create admission capacity`() {
        val (ws, _) = Fixtures.provisionTenant(jdbc)
        val policy = RatePolicy(capacity = 5, refillPerSecond = 0.001)
        // At every instant, not only at the end: an UPDATE that would hold more than
        // capacity fails, and that refund (or spend) would be reported as a failure.
        val guard = "refund_cap_${ws.value.toString().take(8)}"
        jdbc.sql("ALTER TABLE rate_bucket ADD CONSTRAINT $guard CHECK (workspace_id <> '${ws.value}' OR tokens <= 5) NOT VALID").update()
        val threads = 12
        val rounds = 6
        val kept = AtomicInteger()
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val nodes = (1..threads).map { independentNode(policy) }
        val started = dbNow()
        try {
            repeat(rounds) { round ->
                val gate = CountDownLatch(1)
                val futures =
                    nodes.mapIndexed { i, node ->
                        pool.submit {
                            gate.await(10, TimeUnit.SECONDS)
                            runCatching {
                                if (node.tryConsume(ws, RateCategory.INGEST).allowed) {
                                    // Most attempts are deferred before T1 and refunded, each racing the others'
                                    // spends and refunds; in the last round one attempt per node keeps its charge.
                                    if (round == rounds - 1 &&
                                        i % 2 == 0
                                    ) {
                                        kept.incrementAndGet()
                                    } else {
                                        node.refund(listOf(RateCharge(ws, RateCategory.INGEST)))
                                    }
                                }
                            }.onFailure { failures.incrementAndGet() }
                        }
                    }
                gate.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
                (tokens(ws)!! <= policy.capacity.toDouble()) shouldBe true
            }
        } finally {
            pool.shutdownNow()
            jdbc.sql("ALTER TABLE rate_bucket DROP CONSTRAINT $guard").update()
        }
        val elapsedSeconds = Duration.between(started, dbNow()).toMillis() / 1000.0

        failures.get() shouldBe 0
        // The envelope: every token kept was admitted within capacity + refill x elapsed.
        (kept.get() <= policy.capacity + policy.refillPerSecond * elapsedSeconds) shouldBe true
        // And the deferrals cost nothing: the bucket holds exactly what the kept charges left.
        tokens(ws)!! shouldBe ((policy.capacity - kept.get()).toDouble() plusOrMinus (policy.refillPerSecond * elapsedSeconds + 1e-3))
        // After the scenario no more than what remains can still be admitted.
        val after = (1..10).count { limiter(policy).tryConsume(ws, RateCategory.INGEST).allowed }
        (kept.get() + after <= policy.capacity + policy.refillPerSecond * (Duration.between(started, dbNow()).toMillis() / 1000.0)) shouldBe
            true
    }
}
