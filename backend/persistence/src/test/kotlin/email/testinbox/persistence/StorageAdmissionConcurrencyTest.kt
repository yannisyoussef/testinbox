package email.testinbox.persistence

import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import email.testinbox.persistence.AdmissionScenarios.F
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.SQLException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * ADR-035 §4, §11 and §17 test 11: concurrent T1s never over-admit, at any
 * boundary, because they serialize on the global admission lock and read
 * their figures only after it is granted. No row lock is involved.
 */
class StorageAdmissionConcurrencyTest : PersistenceIntegrationTest() {
    @Autowired lateinit var admin: JdbcClient
    private lateinit var fx: AdmissionFixture

    @BeforeEach
    fun database() {
        fx = AdmissionFixture(LedgerTestDatabase.create(postgres, admin))
    }

    /** Pauses inside T1, after the read and before the insert, until released. */
    private class Paused(
        private val delegate: StorageAdmissionStore,
    ) : StorageAdmissionStore {
        val reachedDecision = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun <R : Any> admit(
            scope: StorageAdmissionScope,
            decide: (StorageUsageSnapshot) -> StorageAdmissionPlan<R>,
        ): R =
            delegate.admit(scope) { snapshot ->
                reachedDecision.countDown()
                check(release.await(60, TimeUnit.SECONDS)) { "never released" }
                decide(snapshot)
            }
    }

    /** A test-only mutant: T1 without the global admission lock. Never shipped. */
    class LockBypassingAdmission(
        fx: AdmissionFixture,
    ) : JdbcStorageAdmission(fx.db.jdbc, fx.db.transactions) {
        override fun acquireAdmissionLock() = Unit
    }

    data class Race(
        val first: StorageAdmissionResult,
        val second: StorageAdmissionResult,
        /** Whether the second T1 was seen blocked while the first held its decision open. */
        val secondWasBlocked: Boolean,
    )

    /**
     * T1 `first` reads, then pauses holding whatever it holds. T1 `second`
     * then starts. Only after `second` is either blocked or finished does
     * `first` continue. No sleep decides the interleaving.
     */
    private fun race(
        policy: StorageCapacityPolicy,
        first: List<Pair<UUID, UUID>>,
        second: List<Pair<UUID, UUID>>,
        store: () -> JdbcStorageAdmission = { fx.store() },
    ): Race {
        val paused = Paused(store())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a =
                pool.submit<StorageAdmissionResult> {
                    StorageAdmission(
                        paused,
                        policy,
                        StorageEnforcement.ALL,
                    ).admit(fx.request(F, first.map { fx.candidate(it.first, it.second) }))
                }
            check(paused.reachedDecision.await(30, TimeUnit.SECONDS)) { "the first T1 never reached its decision" }
            val b =
                pool.submit<StorageAdmissionResult> {
                    StorageAdmission(
                        store(),
                        policy,
                        StorageEnforcement.ALL,
                    ).admit(fx.request(F, second.map { fx.candidate(it.first, it.second) }))
                }
            val blocked = awaitBlockedOrDone(b)
            paused.release.countDown()
            return Race(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS), blocked)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitBlockedOrDone(future: Future<*>): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (future.isDone) return false
            val blocked =
                fx.db.jdbc
                    .sql(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0",
                    ).query(Int::class.java)
                    .single()
            if (blocked > 0) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5))
        }
        error("the second T1 neither blocked nor finished")
    }

    // --- the three boundaries --------------------------------------------------------------

    @Test
    fun `global boundary - one copy of headroom, two concurrent T1s, exactly one admitted`() {
        val db = fx.db
        val a = db.workspace()
        val b = db.workspace()
        val filler = db.workspace()
        fx.base(filler, db.inbox(filler), 990)
        val policy = policy(workspace = 1_000_000, global = 1_500, h = 500) // cap 1 000: room for one copy

        val race = race(policy, listOf(a to db.inbox(a)), listOf(b to db.inbox(b)))

        race.secondWasBlocked shouldBe true // on the admission lock: this is what serializes them
        (race.first.shape() + race.second.shape()) shouldContainExactlyInAnyOrder listOf("admitted", "SERVICE_CAPACITY")
        race.first.shape() shouldBe listOf("admitted") // the lock holder decided first
        (990 + fx.reservedBytes() <= policy.globalAdmissionCapBytes) shouldBe true
    }

    @Test
    fun `workspace boundary - two events for different inboxes of one workspace, exactly one admitted`() {
        val db = fx.db
        val ws = db.workspace()
        fx.base(ws, db.inbox(ws), 90)
        val policy = policy(workspace = 100, global = 1_000_000)

        val race = race(policy, listOf(ws to db.inbox(ws)), listOf(ws to db.inbox(ws)))

        race.secondWasBlocked shouldBe true
        race.first.shape() shouldBe listOf("admitted")
        race.second.shape() shouldBe listOf("WORKSPACE_LIMIT")
        fx.reservedBytesIn(ws) shouldBe F
    }

    @Test
    fun `inbox boundary - two events for one inbox, exactly one admitted`() {
        val db = fx.db
        val ws = db.workspace()
        val inbox = db.inbox(ws)
        fx.base(ws, inbox, 40)
        val policy = policy(workspace = 1_000, share = "0.05", global = 1_000_000) // inbox 50

        val race = race(policy, listOf(ws to inbox), listOf(ws to inbox))

        race.secondWasBlocked shouldBe true
        race.first.shape() shouldBe listOf("admitted")
        race.second.shape() shouldBe listOf("INBOX_LIMIT")
    }

    @Test
    fun `without the admission lock the same race over-admits, so the tests above can fail`() {
        val db = fx.db
        val a = db.workspace()
        val b = db.workspace()
        val filler = db.workspace()
        fx.base(filler, db.inbox(filler), 990)
        val policy = policy(workspace = 1_000_000, global = 1_500, h = 500)

        val race = race(policy, listOf(a to db.inbox(a)), listOf(b to db.inbox(b))) { LockBypassingAdmission(fx) }

        race.secondWasBlocked shouldBe false // nothing serialized it
        race.first.shape() shouldBe listOf("admitted")
        race.second.shape() shouldBe listOf("admitted")
        (990 + fx.reservedBytes() > policy.globalAdmissionCapBytes) shouldBe true // over-admitted
    }

    // --- many workspaces, many threads -------------------------------------------------------------

    @Test
    fun `concurrent events across many workspaces never exceed any ceiling and never deadlock`() {
        val db = fx.db
        val workspaces = List(8) { db.workspace() }
        val inboxes = workspaces.associateWith { ws -> List(3) { db.inbox(ws) } }
        // Every ceiling is contested. Inbox: 3 copies. Workspace: 6 copies, so the
        // eight workspaces could hold 48. Global cap: 40 copies, so it binds last.
        // Offered: 16 threads × 12 events × 1–5 copies ≈ 580 copies.
        val policy = policy(workspace = 6 * F, share = "0.5", global = 40 * F)
        val admission = fx.admission(policy)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val admittedCopies = AtomicInteger()
        val refusals = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        repeat(16) { thread ->
            pool.submit {
                val random = Random(thread)
                start.await()
                repeat(12) {
                    // Distinct inboxes: an event carries at most one copy per inbox.
                    val event =
                        inboxes
                            .flatMap { (ws, ibs) -> ibs.map { ws to it } }
                            .shuffled(random)
                            .take(random.nextInt(1, 6))
                            .map { (ws, inbox) -> fx.candidate(ws, inbox) }
                    runCatching { admission.admit(fx.request(F, event)) }
                        .onSuccess { result ->
                            admittedCopies.addAndGet(result.admitted.size)
                            refusals += result.refused.map { it.reason.name }
                        }.onFailure { failures += it }
                }
            }
        }
        try {
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.MINUTES) shouldBe true
        } finally {
            pool.shutdownNow()
        }

        failures.map { f ->
            generateSequence(
                f,
            ) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList() to f.message
        } shouldBe
            emptyList()
        withClue("demand exceeded the global cap, so it is filled exactly") {
            fx.reservedBytes() shouldBe policy.globalAdmissionCapBytes
            admittedCopies.get() shouldBe 40
        }
        workspaces.forEach { ws -> (fx.reservedBytesIn(ws) <= policy.workspaceLimitBytes) shouldBe true }
        db.jdbc
            .sql("SELECT coalesce(max(s), 0) FROM (SELECT sum(bytes) AS s FROM storage_reservation GROUP BY inbox_id) x")
            .query(Long::class.java)
            .single()
            .let { (it <= policy.inboxLimitBytes) shouldBe true }
        refusals.toSet().all { it in setOf("INBOX_LIMIT", "WORKSPACE_LIMIT", "SERVICE_CAPACITY") } shouldBe true
        println("ADR-035 T1 concurrency: ${admittedCopies.get()} admitted, refusals ${refusals.groupingBy { it }.eachCount()}, 0 failures")
    }
}
