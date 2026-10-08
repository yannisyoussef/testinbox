package email.testinbox.domain.storage

import email.testinbox.domain.storage.FootprintAdmission.Load
import email.testinbox.domain.storage.FootprintAdmission.Snapshot
import email.testinbox.domain.storage.FootprintAdmission.Verdict
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The filesystem-containment admission rules (contract §2.1, §2.4). The point is
 * not that F bounds a SET (FootprintModelTest proves that), but that admitting
 * on the exact post-admission aggregate keeps the filesystem contained across
 * arbitrary operation sequences, which a per-copy φ charge does not.
 */
class FootprintAdmissionTest {
    private val kib = 1024L
    private val model = FootprintModel.REFERENCE

    private fun limits(
        g: Long,
        c: Long,
        h: Long = 0,
        m: Long = 0,
        r: Long = 0,
    ) = FootprintAdmission.Limits(g, h, m, r, c)

    @Test
    fun `the owner's counterexample - a phi charge admits what the aggregate refuses`() {
        // φ(4096) = 28 688, but F(4096, 1) − F(0, 0) = 32 800.
        model.ofObject(4096) shouldBe 28_688
        model.bound(4096, 1) - model.bound(0, 0) shouldBe 32_800
        val headroom = 30_000L
        val lim = limits(g = Long.MAX_VALUE, c = headroom)
        val empty = Snapshot(Load.ZERO, Load.ZERO, trashBytes = 0)

        // The old rule: F(L) + φ(copy) ≤ capacity. It would admit...
        (model.bound(0, 0) + model.ofObject(4096) <= headroom) shouldBe true
        // ...and the aggregate would then exceed the capacity. The corrected rule refuses.
        FootprintAdmission.decide(model, lim, empty, listOf(Load(4096, 1))) shouldBe listOf(Verdict.CONTAINMENT)
        FootprintAdmission.decide(model, limits(g = Long.MAX_VALUE, c = 32_800), empty, listOf(Load(4096, 1))) shouldBe
            listOf(Verdict.ADMITTED)
    }

    @Test
    fun `copies of one event are decided against running totals - the event as a whole fits`() {
        val copy = Load(10 * kib, 2)
        val one = model.bound(copy.bytes, copy.objects)
        val two = model.bound(2 * copy.bytes, 2 * copy.objects)
        val lim = limits(g = two + 5, c = Long.MAX_VALUE)
        FootprintAdmission.decide(model, lim, Snapshot(Load.ZERO, Load.ZERO, 0), List(3) { copy }) shouldBe
            listOf(Verdict.ADMITTED, Verdict.ADMITTED, Verdict.GLOBAL_FOOTPRINT)
        (one < two) shouldBe true
    }

    @Test
    fun `a refused copy adds nothing - a smaller later copy can still fit`() {
        val big = Load(64 * kib, 1)
        val small = Load(0, 1)
        val lim = limits(g = model.bound(0, 1), c = Long.MAX_VALUE)
        FootprintAdmission.decide(model, lim, Snapshot(Load.ZERO, Load.ZERO, 0), listOf(big, small)) shouldBe
            listOf(Verdict.GLOBAL_FOOTPRINT, Verdict.ADMITTED)
    }

    @Test
    fun `rule G bounds the live footprint, rule C the whole potential - debt reduces the ceiling`() {
        val live = Load(100 * kib, 4)
        val debt = Load(50 * kib, 3)
        val copy = Load(4 * kib, 1)
        val liveAfter = model.bound(live.bytes + copy.bytes, live.objects + copy.objects)
        val allAfter = model.bound(live.bytes + debt.bytes + copy.bytes, live.objects + debt.objects + copy.objects)
        val trash = 7_000L
        val h = 1_000L
        val m = 2_000L
        val r = 3_000L
        val snapshot = Snapshot(live, debt, trash)
        // Exactly at both boundaries: admitted.
        FootprintAdmission.decide(
            model,
            limits(g = liveAfter + h, c = allAfter + trash + h + m + r, h = h, m = m, r = r),
            snapshot,
            listOf(copy),
        ) shouldBe
            listOf(Verdict.ADMITTED)
        // One byte under (G).
        FootprintAdmission.decide(
            model,
            limits(g = liveAfter + h - 1, c = Long.MAX_VALUE, h = h, m = m, r = r),
            snapshot,
            listOf(copy),
        ) shouldBe
            listOf(Verdict.GLOBAL_FOOTPRINT)
        // One byte under (C): the debt, not the live set, is what refuses.
        FootprintAdmission.decide(
            model,
            limits(g = Long.MAX_VALUE, c = allAfter + trash + h + m + r - 1, h = h, m = m, r = r),
            snapshot,
            listOf(copy),
        ) shouldBe
            listOf(Verdict.CONTAINMENT)
    }

    @Test
    fun `no observation, no admission - without W there is no bound on trash`() {
        FootprintAdmission.decide(
            model,
            limits(g = Long.MAX_VALUE, c = Long.MAX_VALUE),
            Snapshot(Load.ZERO, Load.ZERO, null),
            listOf(Load(0, 1)),
        ) shouldBe
            listOf(Verdict.UNOBSERVED)
        FootprintAdmission.potential(model, Snapshot(Load.ZERO, Load.ZERO, null)) shouldBe null
    }

    @Test
    fun `an overflowing aggregate refuses, never wraps`() {
        val huge = Snapshot(Load(Long.MAX_VALUE / 2, 1), Load.ZERO, 0)
        FootprintAdmission.decide(model, limits(g = Long.MAX_VALUE, c = Long.MAX_VALUE), huge, listOf(Load(Long.MAX_VALUE / 2, 1))) shouldBe
            listOf(Verdict.CONTAINMENT)
    }

    @Test
    fun `the aggregate increment is between phi and F of the copy (Lemma 2)`() {
        runBlocking {
            checkAll(2_000, Arb.long(0L, 1L shl 30), Arb.long(0L, 10_000L), Arb.long(0L, 15L * 1024 * 1024)) { p, n, x ->
                val delta = model.bound(p + x, n + 1) - model.bound(p, n)
                (delta >= model.ofObject(x)) shouldBe true
                (delta <= model.bound(x, 1)) shouldBe true
            }
        }
    }

    // --- containment across arbitrary operation sequences (contract §2.4, the theorem) -----------

    /**
     * A worst-case world: every object physically occupies exactly φ of its
     * payload, an observation's measurement misses every trash move after it
     * began, and late objects surface whenever the H bound allows. After EVERY
     * operation, TestInbox's physical bytes must stay within C_fs − R_ops − M.
     */
    private class World(
        private val model: FootprintModel,
        private val limits: FootprintAdmission.Limits,
        private val slots: Int,
        private val maxObject: Long,
    ) {
        var t = 0L

        class Reservation(
            val objects: List<Long>,
            var uploaded: Boolean = false,
        )

        class Debt(
            val bytes: Long,
            val objects: Long,
            var incurredAt: Long,
            var pending: Boolean,
        )

        val reservations = mutableListOf<Reservation>()
        val committed = mutableListOf<List<Long>>()
        val trash = mutableListOf<Pair<Long, Long>>() // physical bytes, moved at
        val pendingLate = mutableListOf<Long>() // objects of released ambiguous reservations that may still surface
        val uncovered = mutableListOf<Long>()
        val debts = mutableListOf<Debt>()
        var observation: Pair<Long, Long> = 0L to 0L // started at, trash bytes measured

        fun tick() = t++

        fun snapshot(): Snapshot {
            val liveObjects = committed + reservations.map { it.objects }
            val live = liveObjects.fold(Load.ZERO) { acc, objs -> acc + Load(objs.sum(), objs.size.toLong()) }
            val debt =
                debts
                    .filter { it.pending || it.incurredAt >= observation.first }
                    .fold(Load.ZERO) { acc, d -> acc + Load(d.bytes, d.objects) }
            return Snapshot(live, debt, observation.second)
        }

        fun physical(): Long =
            committed.flatten().sumOf(model::ofObject) +
                reservations.filter { it.uploaded }.flatMap { it.objects }.sumOf(model::ofObject) +
                uncovered.sumOf(model::ofObject) +
                trash.sumOf { it.first }

        private fun moveToTrash(objects: List<Long>) {
            objects.forEach { trash += model.ofObject(it) to tick() }
        }

        private fun debtAfter(objects: List<Long>) {
            debts += Debt(objects.sum(), objects.size.toLong(), tick(), pending = false)
        }

        @Suppress("CyclomaticComplexMethod") // one branch per operation of the state machine, by design
        fun step(
            op: Int,
            rnd: Random,
        ) {
            when (op) {
                0, 1 -> {
                    val objects =
                        List(1 + rnd.nextInt(3)) {
                            if (rnd.nextBoolean()) {
                                rnd.nextLong(maxObject + 1)
                            } else {
                                rnd.nextLong(5) * 4096 +
                                    1
                            }
                        }.map { it.coerceAtMost(maxObject) }
                    val copies = 1 + rnd.nextInt(3)
                    val verdicts =
                        FootprintAdmission.decide(model, limits, snapshot(), List(copies) { Load(objects.sum(), objects.size.toLong()) })
                    verdicts.filter { it == Verdict.ADMITTED }.forEach { reservations += Reservation(objects) }
                }

                2 -> {
                    reservations.filter { !it.uploaded }.randomOrNull(rnd)?.uploaded = true
                }

                3 -> {
                    reservations.filter { it.uploaded }.randomOrNull(rnd)?.let {
                        // T2: a transfer
                        reservations.remove(it)
                        committed += it.objects
                    }
                }

                4 -> {
                    reservations.randomOrNull(rnd)?.let {
                        // definitive failure or duplicate: deleted, proven, released
                        reservations.remove(it)
                        if (it.uploaded) moveToTrash(it.objects)
                        debtAfter(it.objects)
                    }
                }

                5 -> {
                    // ADR-035 §9: an unresolved ambiguity holds a write slot until it is verified,
                    // so at most `slots` objects can surface late (pending or already surfaced).
                    if (pendingLate.size + uncovered.size < slots) {
                        reservations.filter { !it.uploaded }.randomOrNull(rnd)?.let {
                            // ambiguous: proven absent at release, may still surface
                            reservations.remove(it)
                            debtAfter(it.objects)
                            pendingLate += it.objects.max()
                        }
                    }
                }

                6 -> {
                    if (pendingLate.isNotEmpty()) uncovered += pendingLate.removeAt(0) // surfaces late
                }

                7 -> {
                    committed.randomOrNull(rnd)?.let {
                        // retention: blobs first, then rows (trigger debt)
                        committed.remove(it)
                        moveToTrash(it)
                        debtAfter(it)
                    }
                }

                8 -> {
                    uncovered.randomOrNull(rnd)?.let {
                        // orphan / verifier: pending row, delete, prove, re-stamp
                        val row = Debt(it, 1, tick(), pending = true)
                        debts += row
                        uncovered.remove(it)
                        moveToTrash(listOf(it))
                        row.incurredAt = tick()
                        row.pending = false
                    }
                }

                9 -> {
                    if (trash.isNotEmpty()) trash.removeAt(rnd.nextInt(trash.size))
                }

                // purge

                10 -> { // observe: the measurement sees only what was in trash when it began
                    val start = tick()
                    observation = start to trash.filter { it.second < start }.sumOf { it.first }
                }

                11 -> {
                    debts.removeAll { !it.pending && it.incurredAt < observation.first }
                }

                // debt compaction

                12 -> {
                    if (pendingLate.isNotEmpty()) pendingLate.removeAt(0) // verified absent at T_verify: the slot frees
                }
            }
        }

        private fun <T> List<T>.randomOrNull(rnd: Random): T? = if (isEmpty()) null else this[rnd.nextInt(size)]
    }

    @Test
    fun `containment holds after every step of arbitrary operation sequences`() {
        val maxObject = 64 * kib
        val slots = 2
        val h = slots * model.bound(maxObject, 1)
        val gF = 2L * 1024 * 1024
        val m = 100 * kib
        val r = 200 * kib
        val capacity = gF + 1024 * kib + m + r
        val limits = FootprintAdmission.Limits(gF, h, m, r, capacity)
        runBlocking {
            checkAll(1_000, Arb.long(), Arb.list(Arb.int(0, 12), 50..400)) { seed, ops ->
                val world = World(model, limits, slots, maxObject)
                val rnd = Random(seed)
                ops.forEach { op ->
                    val before = world.reservations.size
                    world.step(op, rnd)
                    val potential = checkNotNull(FootprintAdmission.potential(model, world.snapshot()))
                    val state =
                        "op=$op physical=${world.physical()} potential=$potential uncovered=${world.uncovered} " +
                            "trash=${world.trash} debts=${world.debts.map {
                                "${it.bytes}/${it.objects}@${it.incurredAt}${if (it.pending) "p" else ""}"
                            }} " +
                            "observation=${world.observation} pendingLate=${world.pendingLate}"
                    // The theorem: TestInbox's physical bytes stay contained.
                    withClue("theorem: $state") { (world.physical() <= capacity - r - m) shouldBe true }
                    // Lemma 1, the ordering lemma included: physical ≤ Φ + the uncovered late objects.
                    withClue("lemma 1: $state") { (world.physical() <= potential + world.uncovered.sumOf(model::ofObject)) shouldBe true }
                    // The invariant admission maintains: right after an admission, Φ + H_F + M + R_ops ≤ C_fs.
                    if (world.reservations.size >
                        before
                    ) {
                        withClue("admission: $state") { (potential + h + m + r <= capacity) shouldBe true }
                    }
                }
            }
        }
    }

    @Test
    fun `the same worlds under the old phi rule breach containment - the regression the owner found`() {
        // Admitting on F(L + D) + W + φ(copy) instead of the post-admission aggregate.
        val b = 4096L
        val capacity = 30_000L
        val physicalAfterPhiRule =
            run {
                val phiAdmits = model.bound(0, 0) + model.ofObject(b) <= capacity
                if (phiAdmits) model.bound(b, 1) else 0
            }
        // The aggregate the admitted copy must be covered by exceeds the capacity.
        (physicalAfterPhiRule > capacity) shouldBe true
    }
}
