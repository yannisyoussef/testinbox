package email.testinbox.persistence

import email.testinbox.application.port.StorageAdmissionPlan
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.port.StorageUsageSnapshot
import email.testinbox.application.port.TransactionRunner
import email.testinbox.application.storage.AdmissionLocking
import email.testinbox.application.storage.EffectiveStoragePolicy
import email.testinbox.application.storage.FilesystemDeclarations
import email.testinbox.application.storage.StorageDeclarations
import email.testinbox.application.storage.StorageFootprintUnavailableException
import email.testinbox.application.usecase.ReserveCohortStorage
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.WorkspaceId
import email.testinbox.domain.storage.LegacyStorageShare
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.SQLException
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * The share-partitioned T1 (PROPOSED ADR-035 amendment, gate D W1;
 * docs/adr/0035-benchmark/partitioned-t1-correctness.md) on PostgreSQL:
 * - in a closed, approved, non-overcommitted cohort it partitions, never
 *   over-admits any share under concurrency, and decides exactly as mode a;
 * - everything that needs the global lock still excludes it (lifecycle drain
 *   fence, rule (P), share growth);
 * - cohort growth is refused past the recorded bound, serialized with every
 *   in-flight T1, and otherwise forces mode a;
 * - and each safeguard is load-bearing: removing it makes a test fail.
 * No sleep decides any interleaving: latches, and pg_stat_activity's blocked
 * sessions, do.
 */
class PartitionedAdmissionTest : PartitionedAdmissionFixture() {
    // --- 1. a closed cohort partitions, and decides as mode a ------------------------------------

    @Test
    fun `a closed approved cohort is admitted on the partitioned path, decided exactly as mode a decides`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        val store = partitioned()
        // 12 events on one workspace: the payload share holds ten copies; then a two-workspace event.
        val shapes = (1..12).map { admit(store, a to cohort.inboxes.getValue(a)[it % 3]).shape() }
        val mixed = admit(store, a to cohort.inboxes.getValue(a)[0], b to cohort.inboxes.getValue(b)[0]).shape()
        store.partitionedAdmissions shouldBe 13
        store.partitionFallbacks shouldBe 0
        fx.reservedBytesIn(a) shouldBe payloadShare

        // The same sequence on a fresh, identical cohort under mode a.
        db = LedgerTestDatabase.create(postgres, admin)
        fx = AdmissionFixture(db)
        val again = closedCohort()
        val (a2, b2) = again.workspaces
        val modeA = modeA()
        val shapesA = (1..12).map { admit(modeA, a2 to again.inboxes.getValue(a2)[it % 3]).shape() }
        val mixedA = admit(modeA, a2 to again.inboxes.getValue(a2)[0], b2 to again.inboxes.getValue(b2)[0]).shape()
        shapes shouldBe shapesA
        mixed shouldBe mixedA
        shapes shouldBe List(10) { listOf("admitted") } + List(2) { listOf("WORKSPACE_LIMIT") }
        mixed shouldBe listOf("WORKSPACE_LIMIT", "admitted")
    }

    @Test
    fun `switch off - the GLOBAL store never partitions and serializes T1s of different workspaces on the exclusive lock`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        val global = modeA()
        val off = race(global, listOf(a to cohort.inboxes.getValue(a)[0])) { admit(global, b to cohort.inboxes.getValue(b)[0]) }
        off.secondWasBlocked shouldBe true
        global.partitionedAdmissions shouldBe 0
        global.partitionFallbacks shouldBe 0
        global.partitionedStatement shouldBe null
        // The partitioned store lets the same two workspaces proceed in parallel.
        val store = partitioned()
        val on = race(store, listOf(a to cohort.inboxes.getValue(a)[1])) { admit(store, b to cohort.inboxes.getValue(b)[1]) }
        on.secondWasBlocked shouldBe false
        (on.first.shape() + on.second.shape()) shouldBe listOf("admitted", "admitted")
        store.partitionedAdmissions shouldBe 2
    }

    // --- 2. no overcommit under concurrent per-workspace admissions ------------------------------

    @Test
    fun `same workspace - the second T1 waits on the workspace lock and sees the first, so the share is never exceeded`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val inboxes = cohort.inboxes.getValue(ws)
        val store = partitioned()
        repeat(9) { admit(store, ws to inboxes[it % 3]) } // one copy of headroom left
        val race = race(store, listOf(ws to inboxes[0])) { admit(store, ws to inboxes[1]) }
        race.secondWasBlocked shouldBe true
        race.first.shape() shouldBe listOf("admitted")
        race.second.shape() shouldBe listOf("WORKSPACE_LIMIT")
        fx.reservedBytesIn(ws) shouldBe payloadShare
        store.partitionFallbacks shouldBe 0
    }

    @Test
    fun `without the per-workspace lock the same race over-admits, so the test above can fail`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val inboxes = cohort.inboxes.getValue(ws)
        val mutant =
            object : JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED) {
                override fun acquireWorkspaceLocks(keys: List<Int>) = Unit
            }
        repeat(9) { admit(mutant, ws to inboxes[it % 3]) }
        val race = race(mutant, listOf(ws to inboxes[0])) { admit(mutant, ws to inboxes[1]) }
        race.secondWasBlocked shouldBe false
        (race.first.shape() + race.second.shape()) shouldBe listOf("admitted", "admitted")
        (fx.reservedBytesIn(ws) > payloadShare) shouldBe true // over-admitted
    }

    @Test
    fun `many threads, many workspaces, multi-workspace events - no share and no total ever exceeds its bound`() {
        val cohort = closedCohort(4)
        val store = partitioned()
        val threads = 12
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        try {
            val futures =
                (0 until threads).map { t ->
                    pool.submit {
                        val random = Random(t)
                        check(start.await(30, TimeUnit.SECONDS))
                        repeat(15) {
                            // Distinct workspaces, one inbox each, in a random order: keys are sorted inside T1.
                            val chosen = cohort.workspaces.shuffled(random).take(1 + random.nextInt(3))
                            runCatching { admit(store, *chosen.map { it to cohort.inboxes.getValue(it).random(random) }.toTypedArray()) }
                                .onFailure { failures += it }
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        failures shouldBe emptyList()
        cohort.workspaces.forEach { ws -> (fx.reservedBytesIn(ws) <= payloadShare) shouldBe true }
        fx.reservedBytes() shouldBe cohort.workspaces.size * payloadShare // every share filled exactly, none exceeded
        store.partitionFallbacks shouldBe 0
        // No deadlock: every T1 completed, and none was cut off by PostgreSQL's detector.
        failures.none { f -> generateSequence(f) { it.cause }.filterIsInstance<SQLException>().any { it.sqlState == "40P01" } } shouldBe
            true
    }

    // --- 3. everything that needs (35, 1) EXCLUSIVE still excludes the partitioned T1 ------------

    @Test
    fun `the lifecycle drain fence waits for an in-flight partitioned T1, and a partitioned T1 waits for the fence`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val store = partitioned()
        val fence = JdbcAccountLifecycle(db.jdbc)
        val race = race(store, listOf(ws to cohort.inboxes.getValue(ws)[0])) { db.transactions.execute { fence.lockStorageFence() } }
        race.secondWasBlocked shouldBe true
        race.first.shape() shouldBe listOf("admitted")

        // The other direction: the fence held, then a partitioned T1 of an unrelated workspace.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder =
                pool.submit {
                    db.transactions.execute {
                        fence.lockStorageFence()
                        held.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                    }
                }
            check(held.await(30, TimeUnit.SECONDS))
            val other = cohort.workspaces.last()
            val t1 = pool.submit<StorageAdmissionResult> { admit(store, other to cohort.inboxes.getValue(other)[0]) }
            awaitBlockedOrDone(t1) shouldBe true
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            t1.get(30, TimeUnit.SECONDS).shape() shouldBe listOf("admitted")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `rule P waits for an in-flight partitioned T1 on the exclusive admission lock`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val ruleP = JdbcRowFreeDebtStore(db.jdbc, db.transactions)
        val race =
            race(partitioned(), listOf(ws to cohort.inboxes.getValue(ws)[0])) {
                ruleP.admit("_probe/test/1", 0, 1, "witness") { true }
            }
        race.secondWasBlocked shouldBe true
        race.second shouldBe true
    }

    // --- 4. cohort growth --------------------------------------------------------------------------

    @Test
    fun `raising shares past the recorded partition bound is refused at commit, whoever writes`() {
        val cohort = closedCohort()
        val (payloadBound, _) = legacyBound()
        val failure =
            shouldThrow<Exception> {
                db.jdbc
                    .sql("UPDATE storage_legacy_share SET payload_limit_bytes = :p WHERE workspace_id = :ws")
                    .param("p", payloadBound) // alone it is the whole bound: with thirty participant places on top, overcommitted
                    .param("ws", cohort.workspaces.first())
                    .update()
            }
        generateSequence<Throwable>(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain "partition bound"
        db.jdbc
            .sql("SELECT payload_limit_bytes FROM storage_legacy_share WHERE workspace_id = ?")
            .param(cohort.workspaces.first())
            .query(Long::class.java)
            .single() shouldBe payloadShare
        // Nothing changed for admission: still partitioned.
        val store = partitioned()
        admit(store, cohort.workspaces.first() to cohort.inboxes.getValue(cohort.workspaces.first())[0]).shape() shouldBe listOf("admitted")
        store.partitionFallbacks shouldBe 0
    }

    @Test
    fun `without the V25 bound check the same overcommitting growth commits, so the test above can fail`() {
        val cohort = closedCohort()
        val (payloadBound, _) = legacyBound()
        db.jdbc.sql("DROP TRIGGER storage_partition_bound_check ON storage_legacy_share").update()
        db.jdbc
            .sql("UPDATE storage_legacy_share SET payload_limit_bytes = :p WHERE workspace_id = :ws")
            .param("p", payloadBound)
            .param("ws", cohort.workspaces.first())
            .update() shouldBe 1
        // The T1 guard re-checks the bound in its own snapshot all the same: no partitioned decision.
        val store = partitioned()
        shouldThrow<StorageFootprintUnavailableException> {
            admit(store, cohort.workspaces.first() to cohort.inboxes.getValue(cohort.workspaces.first())[0])
        }
        store.partitionFallbacks shouldBe 1
    }

    @Test
    fun `growth within the bound un-approves the pool - the next T1 refuses, in the same snapshot, as mode a refuses`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        db.jdbc
            .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + 1 WHERE workspace_id = ?")
            .param(ws)
            .update() shouldBe 1
        // Not overcommitted (the bound holds), so the database guard lets T1 partition; the pool's
        // fingerprint no longer matches its inventory, so the cohort readiness read in that same
        // snapshot refuses (COHORT_UNAPPROVED, 451) exactly as mode a's does.
        val store = partitioned()
        shouldThrow<StorageFootprintUnavailableException> { admit(store, ws to cohort.inboxes.getValue(ws)[0]) }
        shouldThrow<StorageFootprintUnavailableException> { admit(modeA(), ws to cohort.inboxes.getValue(ws)[0]) }
        store.partitionFallbacks shouldBe 0
        store.partitionedAdmissions shouldBe 0
        fx.reservedBytes() shouldBe 0
    }

    @Test
    fun `a new workspace without a share forces the safe path for its own events`() {
        val cohort = closedCohort()
        val stranger = db.workspace()
        val store = partitioned()
        shouldThrow<StorageFootprintUnavailableException> { admit(store, stranger to db.inbox(stranger)) }
        store.partitionFallbacks shouldBe 1
        fx.reservedBytes() shouldBe 0
        cohort.workspaces.size shouldBe 3
    }

    @Test
    fun `growth during an in-flight partitioned T1 waits for it, so that T1 decides wholly before the growth`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        val race =
            race(partitioned(), listOf(ws to cohort.inboxes.getValue(ws)[0])) {
                db.jdbc
                    .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + 1 WHERE workspace_id = ?")
                    .param(cohort.workspaces.last())
                    .update()
            }
        race.secondWasBlocked shouldBe true
        race.first.shape() shouldBe listOf("admitted")
        race.second shouldBe 1
    }

    @Test
    fun `without the V25 share fence the same growth does not wait, so the test above can fail`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        db.jdbc.sql("DROP TRIGGER storage_partition_share_fence ON storage_legacy_share").update()
        val race =
            race(partitioned(), listOf(ws to cohort.inboxes.getValue(ws)[0])) {
                db.jdbc
                    .sql("UPDATE storage_legacy_share SET payload_limit_bytes = payload_limit_bytes + 1 WHERE workspace_id = ?")
                    .param(cohort.workspaces.last())
                    .update()
            }
        race.secondWasBlocked shouldBe false
    }

    // --- 5. the T1 guard is load-bearing -----------------------------------------------------------

    @Test
    fun `without a cohort the guard sends T1 to mode a, whose global ceiling refuses, and without the guard it over-admits`() {
        // No plan, no participants: the cohort is not required, and only mode a's global sums hold G − H.
        val a = db.workspace()
        val b = db.workspace()
        val filler = db.workspace()
        fx.base(filler, db.inbox(filler), 990)
        val tight = policy(workspace = 1_000_000, global = 1_500, h = 500) // cap 1 000: room for one copy of 10

        fun admitTight(store: StorageAdmissionStore) =
            StorageAdmission(store, tight, StorageEnforcement.ALL).admit(
                fx.request(AdmissionScenarios.F, listOf(fx.candidate(a, db.inbox(a)), fx.candidate(b, db.inbox(b)))),
            )

        val store = partitioned()
        admitTight(store).shape() shouldBe listOf("admitted", "SERVICE_CAPACITY")
        store.partitionFallbacks shouldBe 1
        val guardless =
            object : JdbcStorageAdmission(db.jdbc, db.transactions, readsFootprint = true, locking = AdmissionLocking.COHORT_PARTITIONED) {
                override fun partitionGuard() = PartitionedT1Sql.columns("true", "true", "true", "true")
            }
        admitTight(guardless).shape() shouldBe listOf("admitted", "admitted")
        guardless.partitionedAdmissions shouldBe 1
        (990 + fx.reservedBytes() > tight.globalAdmissionCapBytes) shouldBe true // over the global cap
    }

    @Test
    fun `a plan whose recorded bound is missing or stale is never partitioned`() {
        val cohort = closedCohort()
        val ws = cohort.workspaces.first()
        db.jdbc.sql("UPDATE storage_partition_bound SET fingerprint = repeat('0', 64)").update()
        val store = partitioned()
        admit(store, ws to cohort.inboxes.getValue(ws)[0]).shape() shouldBe listOf("admitted")
        store.partitionFallbacks shouldBe 1
        db.jdbc.sql("DELETE FROM storage_partition_bound").update()
        admit(store, ws to cohort.inboxes.getValue(ws)[1]).shape() shouldBe listOf("admitted")
        store.partitionFallbacks shouldBe 2
        store.partitionedAdmissions shouldBe 0
        // A reserve records the matching bound again, and the cohort partitions.
        reserve(cohort.workspaces.associate { WorkspaceId(it) to LegacyStorageShare(payloadShare, footprintShare) })
        admit(store, ws to cohort.inboxes.getValue(ws)[2]).shape() shouldBe listOf("admitted")
        store.partitionedAdmissions shouldBe 1
    }

    @Test
    fun `the reserve records the bound the declarations leave, for the plan's binding and fingerprint`() {
        closedCohort()
        val plan =
            db.jdbc
                .sql(
                    "SELECT p.binding = b.binding AND p.fingerprint = b.fingerprint FROM storage_cohort_plan p JOIN storage_partition_bound b ON b.id",
                ).query(Boolean::class.java)
                .single()
        plan shouldBe true
        val runtime = checkNotNull(EffectiveStoragePolicy.footprint(declarations)?.cohort)
        val expected = checkNotNull(runtime.partitionCapacity(gib, gib))
        legacyBound() shouldBe (expected.payloadBytes to expected.footprintBytes)
        db.jdbc
            .sql("SELECT storage_partition_within_bound()")
            .query(Boolean::class.java)
            .single() shouldBe true
    }
}
