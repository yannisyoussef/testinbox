package email.testinbox.persistence

import email.testinbox.application.port.EscalationReason
import email.testinbox.application.port.EscrowPath
import email.testinbox.application.port.EscrowRegrant
import email.testinbox.application.port.StorageAdmissionInputException
import email.testinbox.application.port.StorageAdmissionScope
import email.testinbox.application.usecase.StorageAdmissionResult
import email.testinbox.domain.storage.FootprintModel
import email.testinbox.persistence.AdmissionFixture.Companion.policy
import email.testinbox.persistence.AdmissionFixture.Companion.shape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The slot-lane escrow PROTOTYPE on PostgreSQL (ADR-038 PROPOSED; M1, §12.1), over
 * V28-proto applied to a throwaway database. No sleep decides any interleaving:
 * latches and `pg_blocking_pids` do. The remaining §11 decisive tests are M3's.
 */
class EscrowAdmissionTest : EscrowFixture() {
    // --- decisions -------------------------------------------------------------------------------------

    @Test
    fun `the fast path decides exactly as mode a - admissions from grants, refusals only on exact rows by mode a's formula`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        setPolicy()
        val s = Stack()
        val ib = cohort.inboxes
        val shapes = (1..12).map { s.admission().admit(request(it % 16, a to ib.getValue(a)[it % 3])).shape() }
        val mixed = s.admission().admit(request(3, a to ib.getValue(a)[0], b to ib.getValue(b)[0])).shape()
        // The same sequences under mode a (PartitionedAdmissionTest's own expectations for this cohort).
        shapes shouldBe List(10) { listOf("admitted") } + List(2) { listOf("WORKSPACE_LIMIT") }
        mixed shouldBe listOf("WORKSPACE_LIMIT", "admitted")
        fx.reservedBytesIn(a) shouldBe payloadShare
        // The first event found no lane: the slow path decided it and regranted. The rest were fast.
        metrics.escalated(EscalationReason.LANE_MISSING) shouldBe 1
        metrics.taken(EscrowPath.SLOW) shouldBe 1
        (metrics.taken(EscrowPath.FAST) + metrics.taken(EscrowPath.SCOPE_REFILL)) shouldBe 12
        assertEscrowInvariants()
    }

    @Test
    fun `a missing or foreign inbox fails the fast path closed from the owner read after the locks, as mode a`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        setPolicy()
        val s = Stack()
        s.admission().admit(request(0, a to cohort.inboxes.getValue(a)[0])).shape() shouldBe listOf("admitted")
        shouldThrow<StorageAdmissionInputException> { s.admission().admit(request(1, a to cohort.inboxes.getValue(b)[0])) }
        val gone = cohort.inboxes.getValue(a)[2]
        db.hardDeleteInbox(gone)
        shouldThrow<StorageAdmissionInputException> { s.admission().admit(request(2, a to gone)) }
        fx.reservedBytesIn(b) shouldBe 0
        assertEscrowInvariants()
    }

    @Test
    fun `B-8 without a policy row no lane is granted, every event is decided on the slow path as mode a, and none fails`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        val s = Stack()
        val shapes =
            (0 until 4).map {
                s
                    .admission()
                    .admit(
                        request(
                            it,
                            a to cohort.inboxes.getValue(a)[0],
                            b to cohort.inboxes.getValue(b)[1],
                        ),
                    ).shape()
            }
        shapes.flatten().all { it == "admitted" } shouldBe true
        metrics.taken(EscrowPath.SLOW) shouldBe 4
        metrics.escalated(EscalationReason.LANE_MISSING) shouldBe 4
        // No policy: the regrant grants nothing and says why (the alert's source); no lane row exists to spend.
        s.refill.refillGlobal().reason shouldBe "policy_missing"
        db.jdbc
            .sql("SELECT count(*) FROM storage_escrow_lane")
            .query(Long::class.java)
            .single() shouldBe 0
    }

    // --- concurrency (deterministic: latches and pg_blocking_pids, never a sleep) ------------------------

    @Test
    fun `the fast path never admits beyond a scope or the global headroom under concurrent spends and a regrant`() {
        // A global ceiling of 20 copies over three 10-copy shares: the global headroom binds first.
        capacity = policy(workspace = 2 * gib, share = "1", global = 20 * COPY)
        val cohort = closedCohort()
        val (a, b, c) = cohort.workspaces
        setPolicy()
        val s = Stack()
        val ia = cohort.inboxes.getValue(a)
        s.admission().admit(request(0, a to ia[0])).shape() shouldBe listOf("admitted") // slow path: lanes created
        assertEscrowInvariants("after the first regrant")

        // F1 holds lane 1 and a's rows, paused before its decision. F2 (lane 2) queues on a's workspace row.
        // A keeper regrant then queues on lane 1 behind F1's drain. Only then does F1 continue.
        val paused = PausedEscrow(s.store)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val f1 = pool.submit<StorageAdmissionResult> { s.admission(paused).admit(request(1, a to ia[1])) }
            check(paused.reachedDecision.await(30, TimeUnit.SECONDS)) { "F1 never reached its decision" }
            val f2 = pool.submit<StorageAdmissionResult> { s.admission().admit(request(2, a to ia[2])) }
            db.awaitBlockedSessions(1)
            val regrant = pool.submit<EscrowRegrant> { s.refill.refillGlobal() }
            db.awaitBlockedSessions(2)
            paused.release.countDown()
            f1.get(30, TimeUnit.SECONDS).shape() shouldBe listOf("admitted")
            f2.get(30, TimeUnit.SECONDS).shape() shouldBe listOf("admitted")
            regrant.get(30, TimeUnit.SECONDS).granted shouldBe true
        } finally {
            pool.shutdownNow()
        }
        assertEscrowInvariants("after the interleaving")

        // Then 16 lanes at once, 48 one-copy events over the three workspaces: 17 more fit, never one more.
        val workers = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<String>())
        try {
            val futures =
                (0 until 16).map { lane ->
                    workers.submit {
                        check(start.await(30, TimeUnit.SECONDS))
                        repeat(3) { i ->
                            val ws = listOf(a, b, c)[(lane + i) % 3]
                            results +=
                                s
                                    .admission()
                                    .admit(request(lane, ws to cohort.inboxes.getValue(ws)[lane % 3]))
                                    .shape()
                                    .single()
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        results.count { it == "admitted" } shouldBe 17
        results.filter { it != "admitted" }.toSet().all { it in setOf("SERVICE_CAPACITY", "WORKSPACE_LIMIT") } shouldBe true
        fx.reservedBytes() shouldBe 20 * COPY
        listOf(a, b, c).forEach { fx.reservedBytesIn(it) shouldBeLessThanOrEqual payloadShare }
        assertEscrowInvariants("after the parallel phase")
    }

    @Test
    fun `T-C1 the fast path takes no lock on the epoch row, and fast paths on other lanes and scopes never wait for it`() {
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        setPolicy()
        val s = Stack()
        s.admission().admit(request(0, a to cohort.inboxes.getValue(a)[0]))
        val paused = PausedEscrow(s.store)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f1 = pool.submit<StorageAdmissionResult> { s.admission(paused).admit(request(1, a to cohort.inboxes.getValue(a)[1])) }
            check(paused.reachedDecision.await(30, TimeUnit.SECONDS))
            // Behavioural, never pg_locks (2210084): another session takes the epoch row FOR UPDATE NOWAIT.
            db.openTransaction().use { other ->
                other.createStatement().use { it.execute("SELECT 1 FROM storage_admission_epoch WHERE id = 1 FOR UPDATE NOWAIT") }
                other.rollback()
            }
            // A fast path on another lane and another workspace completes while F1 holds its rows.
            s.admission().admit(request(2, b to cohort.inboxes.getValue(b)[0])).shape() shouldBe listOf("admitted")
            paused.release.countDown()
            f1.get(30, TimeUnit.SECONDS).shape() shouldBe listOf("admitted")
        } finally {
            paused.release.countDown()
            pool.shutdownNow()
        }
        metrics.taken(EscrowPath.SLOW) shouldBe 1
    }

    @Test
    fun `E10 a lane epoch bumped between the lock statement and the spend makes the spend escalate, never spend a stale grant`() {
        val cohort = closedCohort()
        val (a) = cohort.workspaces
        setPolicy()
        val s = Stack()
        s.admission().admit(request(0, a to cohort.inboxes.getValue(a)[0]))
        val paused = PausedEscrow(s.store)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val f1 = pool.submit<StorageAdmissionResult> { s.admission(paused).admit(request(1, a to cohort.inboxes.getValue(a)[1])) }
            check(paused.reachedDecision.await(30, TimeUnit.SECONDS))
            // The bump commits while F1 holds its lane: the epoch row is not locked by F1, so it never waits.
            db.jdbc
                .sql("SELECT storage_escrow_bump_for_benchmark(true, false)")
                .query()
                .singleRow()
            paused.release.countDown()
            f1.get(30, TimeUnit.SECONDS).shape() shouldBe listOf("admitted")
        } finally {
            paused.release.countDown()
            pool.shutdownNow()
        }
        metrics.escalated(EscalationReason.SPEND_MISMATCH) shouldBe 1
        metrics.taken(EscrowPath.SLOW) shouldBe 2
        fx.reservedBytesIn(a) shouldBe 2 * COPY
        assertEscrowInvariants()
    }

    // --- H1: the slow path inserts BEFORE it regrants ---------------------------------------------------

    @Test
    fun `H1 the slow path regrants after its insert, and the regrant-before-insert mutant over-grants by the event`() {
        // Real: E4 holds after a slow-path event. The quantum is unbounded, so the lanes take ALL the headroom.
        val cohort = closedCohort()
        val (a, b) = cohort.workspaces
        setPolicy()
        val event = listOf(a to cohort.inboxes.getValue(a)[0], b to cohort.inboxes.getValue(b)[0])
        Stack().admission().admit(request(0, *event.toTypedArray())).shape() shouldBe listOf("admitted", "admitted")
        val real = livePayload() + spendableLanePayload()
        real shouldBeLessThanOrEqual capacity.globalAdmissionCapBytes
        assertEscrowInvariants("real")

        // Mutant: the same slow path, regranting BEFORE its insert (in the scope-lock step) and not after.
        db = LedgerTestDatabase.create(postgres, admin)
        fx = AdmissionFixture(db)
        applyEscrowProto()
        val again = closedCohort()
        val (a2, b2) = again.workspaces
        setPolicy()
        val mutant =
            object : EscrowSlowPath(db.jdbc, inputs) {
                override fun escrowLockScopes(scope: StorageAdmissionScope) {
                    super.escrowLockScopes(scope)
                    super.escrowRegrant(scope, null)
                }

                override fun escrowRegrant(
                    scope: StorageAdmissionScope?,
                    notReady: String?,
                ): EscrowRegrant = EscrowRegrant(0, 0, 0, true, null)
            }
        Stack(slow = mutant)
            .admission()
            .admit(request(0, a2 to again.inboxes.getValue(a2)[0], b2 to again.inboxes.getValue(b2)[0]))
            .shape() shouldBe listOf("admitted", "admitted")
        // The grants were computed from a truth without the event's 2 copies: E4 is broken by them.
        val over = livePayload() + spendableLanePayload() - capacity.globalAdmissionCapBytes
        over shouldBeGreaterThan 0
        over shouldBeLessThanOrEqual 2 * COPY
    }

    // --- P-F: the SQL footprint bound is FootprintModel.bound ---------------------------------------------

    @Test
    fun `P-F storage_footprint_bound equals FootprintModel bound, edges included`() {
        closedCohort(n = 1)
        val models = listOf(footprintPolicy.model, FootprintModel.REFERENCE, FootprintModel(1024, 6 * 1024, 16))
        val random = Random(38)
        for (model in models) {
            val samples =
                listOf(0L to 0L, 0L to 1L, 1L to 1L, model.blockSizeBytes to 1L, model.blockSizeBytes + 1 to 2L, (1L shl 40) to 501L) +
                    List(200) { random.nextLong(0, 1L shl 36) to random.nextLong(0, 10_000) }
            for ((bytes, objects) in samples) {
                db.jdbc
                    .sql("SELECT storage_footprint_bound(?, ?, ?, ?, ?)")
                    .params(bytes, objects, model.blockSizeBytes, model.fragmentationDenominator, model.objectOverheadMaxBytes)
                    .query(Long::class.java)
                    .single() shouldBe model.bound(bytes, objects)
            }
        }
    }
}
