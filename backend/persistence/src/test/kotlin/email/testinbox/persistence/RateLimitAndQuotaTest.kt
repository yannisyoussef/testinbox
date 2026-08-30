package email.testinbox.persistence

import email.testinbox.application.port.RateLimiter
import email.testinbox.application.port.TransactionRunner
import email.testinbox.domain.limits.RateCategory
import email.testinbox.domain.limits.RatePolicy
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADR-027 enforcement against a real PostgreSQL, including the property that
 * matters most: a tenant cannot exceed its allowance by alternating between
 * limiter instances, which is what "multi-node correct" means here.
 */
class RateLimitAndQuotaTest : PersistenceIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var quotaState: JdbcWorkspaceQuotaState

    @Autowired lateinit var waitSlots: JdbcWaitSlots

    @Autowired lateinit var inboxes: JdbcInboxRepository

    @Autowired lateinit var messages: JdbcMessageRepository

    @Autowired lateinit var tx: TransactionRunner

    private fun limiter(policy: RatePolicy): RateLimiter = JdbcRateLimiter(jdbc, transactionManager) { _, _ -> policy }

    private val threeFast = RatePolicy(capacity = 3, refillPerSecond = 1.0)

    @Test
    fun `a bucket allows exactly its capacity, then refuses with a retry hint`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val limiter = limiter(threeFast)
        val outcomes = (1..4).map { limiter.tryConsume(workspaceId, RateCategory.INBOX_CREATE) }
        outcomes.take(3).forEach { it.allowed shouldBe true }
        outcomes[3].allowed shouldBe false
        outcomes[3].remaining shouldBe 0L
        // Retry-After is whole seconds and never zero, or clients hot-loop.
        outcomes[3].retryAfter.shouldNotBeNull().toSeconds() shouldBeGreaterThanOrEqual 1L
    }

    @Test
    fun `two limiter instances share one workspace budget (multi-node correctness)`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        // Two independent limiters = two API nodes against the same database.
        val nodeA = limiter(threeFast)
        val nodeB = limiter(threeFast)
        // Alternating between them must not multiply the allowance.
        val outcomes =
            listOf(
                nodeA.tryConsume(workspaceId, RateCategory.INBOX_CREATE),
                nodeB.tryConsume(workspaceId, RateCategory.INBOX_CREATE),
                nodeA.tryConsume(workspaceId, RateCategory.INBOX_CREATE),
                nodeB.tryConsume(workspaceId, RateCategory.INBOX_CREATE),
            )
        outcomes.count { it.allowed } shouldBe 3
        outcomes[3].allowed shouldBe false
    }

    @Test
    fun `budgets are isolated per workspace and per category`() {
        val (workspaceA, _) = Fixtures.provisionTenant(jdbc)
        val (workspaceB, _) = Fixtures.provisionTenant(jdbc)
        val limiter = limiter(threeFast)
        repeat(3) { limiter.tryConsume(workspaceA, RateCategory.INBOX_CREATE) }
        limiter.tryConsume(workspaceA, RateCategory.INBOX_CREATE).allowed shouldBe false
        // Another tenant is unaffected — one workspace cannot starve another.
        limiter.tryConsume(workspaceB, RateCategory.INBOX_CREATE).allowed shouldBe true
        // And a different cost class has its own budget.
        limiter.tryConsume(workspaceA, RateCategory.DOWNLOAD).allowed shouldBe true
    }

    @Test
    fun `the per-inbox ingest scope does not consume the workspace budget`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId).also { inboxes.insert(it) }
        val limiter = limiter(threeFast)
        repeat(3) { limiter.tryConsume(workspaceId, RateCategory.INGEST, inbox.id) }
        // The inbox's own bucket is empty...
        limiter.tryConsume(workspaceId, RateCategory.INGEST, inbox.id).allowed shouldBe false
        // ...while the workspace-wide bucket is untouched, so a flood against one
        // guessed address cannot starve the workspace's other inboxes.
        limiter.tryConsume(workspaceId, RateCategory.INGEST).allowed shouldBe true
    }

    @Test
    fun `concurrent spends never exceed capacity`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val policy = RatePolicy(capacity = 5, refillPerSecond = 0.001)
        val threads = 20
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    // A fresh limiter per thread: independent nodes, one database.
                    val limiter = limiter(policy)
                    executor.submit<Boolean> {
                        gate.await(5, TimeUnit.SECONDS)
                        limiter.tryConsume(workspaceId, RateCategory.INBOX_CREATE).allowed
                    }
                }
            gate.countDown()
            futures.count { it.get(30, TimeUnit.SECONDS) } shouldBe 5
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `derived usage reflects real rows and is scoped per workspace`() {
        val (workspaceA, projectA) = Fixtures.provisionTenant(jdbc)
        val (workspaceB, _) = Fixtures.provisionTenant(jdbc)
        quotaState.activeInboxCount(workspaceA) shouldBe 0L
        quotaState.storedBytes(workspaceA) shouldBe 0L

        val inbox = Fixtures.inbox(workspaceA, projectA).also { inboxes.insert(it) }
        quotaState.activeInboxCount(workspaceA) shouldBe 1L
        messages.appendVisible(Fixtures.message(inbox))
        // Fixtures.message declares rawSizeBytes = 42.
        quotaState.storedBytes(workspaceA) shouldBe 42L

        // The other tenant sees none of it.
        quotaState.activeInboxCount(workspaceB) shouldBe 0L
        quotaState.storedBytes(workspaceB) shouldBe 0L

        // Deleting frees the inbox allowance immediately, so the 409's advertised
        // remedy actually works rather than waiting for the retention sweep.
        inboxes.markDeleted(workspaceA, inbox.id, Instant.now())
        quotaState.activeInboxCount(workspaceA) shouldBe 0L
    }

    @Test
    fun `derived usage cannot drift when a cascade deletes rows`() {
        val (workspaceId, projectId) = Fixtures.provisionTenant(jdbc)
        val inbox = Fixtures.inbox(workspaceId, projectId).also { inboxes.insert(it) }
        messages.appendVisible(Fixtures.message(inbox))
        quotaState.storedBytes(workspaceId) shouldBe 42L
        // The hard delete cascades and runs no application code — the exact
        // reason a maintained counter was rejected. Derivation just follows.
        inboxes.hardDelete(inbox.id)
        quotaState.storedBytes(workspaceId) shouldBe 0L
    }

    @Test
    fun `wait slots admit exactly the ceiling and refuse the next`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val expiry = Instant.now().plusSeconds(60)
        val held = (1..3).map { waitSlots.acquire(workspaceId, 3, expiry) }
        held.forEach { it.shouldNotBeNull() }
        waitSlots.acquire(workspaceId, 3, expiry) shouldBe null
        // Releasing one frees exactly one.
        held.first()!!.close()
        waitSlots.acquire(workspaceId, 3, expiry).shouldNotBeNull()
    }

    @Test
    fun `concurrent wait-slot claims never exceed the ceiling`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val ceiling = 4L
        val threads = 16
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    executor.submit<Boolean> {
                        gate.await(5, TimeUnit.SECONDS)
                        waitSlots.acquire(workspaceId, ceiling, Instant.now().plusSeconds(60)) != null
                    }
                }
            gate.countDown()
            futures.count { it.get(30, TimeUnit.SECONDS) } shouldBe ceiling.toInt()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `an expired lease is reclaimed so a crashed node cannot shrink the allowance`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        // A slot whose holder died: already expired on arrival.
        waitSlots.acquire(workspaceId, 1, Instant.now().minusSeconds(1)).shouldNotBeNull()
        // The ceiling is 1 and the only slot is stale — the next caller reclaims it.
        waitSlots.acquire(workspaceId, 1, Instant.now().plusSeconds(60)).shouldNotBeNull()
        (waitSlots.reapExpired(Instant.now().plusSeconds(3600)) >= 1) shouldBe true
    }

    @Test
    fun `wait slots are isolated per workspace`() {
        val (workspaceA, _) = Fixtures.provisionTenant(jdbc)
        val (workspaceB, _) = Fixtures.provisionTenant(jdbc)
        val expiry = Instant.now().plusSeconds(60)
        waitSlots.acquire(workspaceA, 1, expiry).shouldNotBeNull()
        waitSlots.acquire(workspaceA, 1, expiry) shouldBe null
        // B's allowance is its own.
        waitSlots.acquire(workspaceB, 1, expiry).shouldNotBeNull()
    }

    @Test
    fun `the admission guard serializes concurrent transactions for one workspace`() {
        val (workspaceId, _) = Fixtures.provisionTenant(jdbc)
        val overlapping =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val maxOverlap =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..8).map {
                    executor.submit {
                        gate.await(5, TimeUnit.SECONDS)
                        tx.required {
                            quotaState.guardAdmission(workspaceId)
                            val inside = overlapping.incrementAndGet()
                            maxOverlap.updateAndGet { current -> maxOf(current, inside) }
                            Thread.sleep(20)
                            overlapping.decrementAndGet()
                        }
                    }
                }
            gate.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            // Never two admission decisions for the same workspace at once — this
            // is what makes derive-then-insert atomic.
            maxOverlap.get() shouldBe 1
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `the guard does not serialize different workspaces`() {
        val (workspaceA, _) = Fixtures.provisionTenant(jdbc)
        val (workspaceB, _) = Fixtures.provisionTenant(jdbc)
        val bothInside = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            listOf(workspaceA, workspaceB).forEach { workspace ->
                executor.submit {
                    tx.required {
                        quotaState.guardAdmission(workspace)
                        bothInside.countDown()
                        // Deadlocks or over-serialization would prevent both arriving.
                        bothInside.await(10, TimeUnit.SECONDS)
                    }
                }
            }
            bothInside.await(20, TimeUnit.SECONDS) shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }
}
