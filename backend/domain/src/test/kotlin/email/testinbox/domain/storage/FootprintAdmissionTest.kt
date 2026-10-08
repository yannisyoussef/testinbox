package email.testinbox.domain.storage

import email.testinbox.domain.storage.FootprintAdmission.Load
import email.testinbox.domain.storage.FootprintAdmission.Snapshot
import email.testinbox.domain.storage.FootprintAdmission.Verdict
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

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
    fun `an overflowing aggregate is indeterminate - an infrastructure refusal, never a capacity verdict, never wrapped`() {
        val huge = Snapshot(Load(Long.MAX_VALUE / 2, 1), Load.ZERO, 0)
        FootprintAdmission.decide(model, limits(g = Long.MAX_VALUE, c = Long.MAX_VALUE), huge, listOf(Load(Long.MAX_VALUE / 2, 1))) shouldBe
            listOf(Verdict.INDETERMINATE)
        // An overflowing copy adds nothing: the next, small copy is still decided on the real totals.
        FootprintAdmission.decide(
            model,
            limits(g = Long.MAX_VALUE, c = Long.MAX_VALUE),
            huge,
            listOf(Load(Long.MAX_VALUE / 2, 1), Load(10, 1)),
        ) shouldBe listOf(Verdict.INDETERMINATE, Verdict.ADMITTED)
    }

    @Test
    fun `Lemma 2 over sets - a copy of several objects raises F by at least the sum of their phi and at most F of the copy`() {
        runBlocking {
            checkAll(
                2_000,
                Arb.long(0L, 1L shl 34),
                Arb.long(0L, 100_000L),
                Arb.list(Arb.long(0L, 15L * 1024 * 1024), 1..8),
            ) { p, n, objects ->
                val copy = Load(objects.sum(), objects.size.toLong())
                val delta = model.bound(p + copy.bytes, n + copy.objects) - model.bound(p, n)
                (delta >= objects.sumOf { model.ofObject(it) }) shouldBe true
                (delta <= model.bound(copy.bytes, copy.objects)) shouldBe true
            }
        }
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
    // Containment across arbitrary interleavings of atomic steps: FootprintWorldTest.
}
