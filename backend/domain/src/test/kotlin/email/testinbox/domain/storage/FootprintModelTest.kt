package email.testinbox.domain.storage

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The filesystem-containment contract §3 (TI-STORAGE-006E): φ is the bound
 * of one object, F(P, N) the closed form the ledger can carry, and the
 * closed form never under-counts a sum of φ. The lab figures PR #81 measured
 * are checked against φ as a sanity floor, not as the proof: the proof is
 * the inequality.
 */
class FootprintModelTest {
    private val kib = 1024L
    private val mib = 1024L * kib
    private val model = FootprintModel.REFERENCE

    @Test
    fun `the reference model is ext4 at 4 KiB with a 24 KiB per-object overhead and a 1 over 256 allowance`() {
        model.blockSizeBytes shouldBe 4 * kib
        model.objectOverheadMaxBytes shouldBe 24 * kib
        model.fragmentationDenominator shouldBe 256
        // K = (B − 1)·(1 + ε) + O_max + 1, rounded up: 4095 + 16 + 24 576 + 1.
        model.perObjectCeilingBytes shouldBe 4095 + 16 + 24 * kib + 1
    }

    @Test
    fun `phi rounds the payload up to blocks, adds the allowance rounded up, then the fixed overhead`() {
        model.ofObject(0) shouldBe 24 * kib
        model.ofObject(1) shouldBe 4 * kib + 16 + 24 * kib
        model.ofObject(4095) shouldBe 4 * kib + 16 + 24 * kib
        model.ofObject(4096) shouldBe 4 * kib + 16 + 24 * kib
        model.ofObject(4097) shouldBe 8 * kib + 32 + 24 * kib
        // 15 MiB = 3 840 blocks; ⌈3840·4096 / 256⌉ = 61 440 B of extent allowance.
        model.ofObject(15 * mib) shouldBe 15 * mib + 60 * kib + 24 * kib
    }

    @Test
    fun `phi covers every per-object figure PR 81's lab measured, with the extent block included`() {
        // (payload, measured physical bytes per object) from fs-lab/RESULTS.md row A.
        val lab =
            listOf(
                512L to 12_308L,
                4 * kib to 16_404L,
                16 * kib to 28_692L,
                64 * kib to 77_865L,
                128 * kib to 143_401L,
                256 * kib to 282_706L,
                mib to 1_069_193L,
                15 * mib to 15_749_530L,
            )
        lab.forEach { (payload, measured) -> model.ofObject(payload) shouldBeGreaterThanOrEqual measured }
    }

    @Test
    fun `tiny objects are dominated by the overhead - the amplification the payload ledger could not see`() {
        // A 512 B payload is bounded at 56× itself; the lab measured 24×.
        model.ofObject(512) / 512 shouldBe 56
        // A zero-byte attachment still costs its directories and xl.meta.
        model.bound(0, 1) shouldBe 4095 + 16 + 24 * kib + 1
    }

    @Test
    fun `the closed form never under-counts a sum of phi`() {
        runBlocking {
            checkAll(500, Arb.list(Arb.long(0L, 15L * mib), 0..60)) { payloads ->
                val exact = payloads.sumOf { model.ofObject(it) }
                model.bound(payloads.sum(), payloads.size.toLong()) shouldBeGreaterThanOrEqual exact
            }
        }
    }

    @Test
    fun `the closed form never under-counts for every supported block size and denominator`() {
        runBlocking {
            checkAll(300, Arb.int(0, 2), Arb.long(16L, 4096L), Arb.list(Arb.long(0L, 2L * mib), 0..40)) { b, d, payloads ->
                val block = listOf(1024L, 2048L, 4096L)[b]
                val m = FootprintModel(block, 6 * block, d)
                val exact = payloads.sumOf { m.ofObject(it) }
                m.bound(payloads.sum(), payloads.size.toLong()) shouldBeGreaterThanOrEqual exact
            }
        }
    }

    @Test
    fun `the closed form is tight to within one block plus one byte per object`() {
        runBlocking {
            checkAll(300, Arb.list(Arb.long(0L, 15L * mib), 1..30)) { payloads ->
                val exact = payloads.sumOf { model.ofObject(it) }
                val bound = model.bound(payloads.sum(), payloads.size.toLong())
                // Slack: at most (B − 1)(1+ε) + 1 per object, plus one for the final rounding.
                ((bound - exact) <= payloads.size * (4095 + 16 + 1) + 1) shouldBe true
            }
        }
    }

    @Test
    fun `the finalize budget is procs times writes times phi of the largest object`() {
        model.finalizeBudgetBytes(1, 16, 15 * mib) shouldBe 16 * model.ofObject(15 * mib)
        model.finalizeBudgetBytes(2, 16, 15 * mib) shouldBe 32 * model.ofObject(15 * mib)
    }

    @Test
    fun `the inode argument - one inode per block suffices because every object takes at least as many blocks as inodes`() {
        // O_max / B = 6 ≥ INODES_PER_OBJECT_MAX, so blocks per object ≥ inodes per object.
        model.objectOverheadMaxBytes / model.blockSizeBytes shouldBe FootprintModel.INODES_PER_OBJECT_MAX
        model.minimumInodes(44L * 1024 * mib) shouldBe 44L * 1024 * mib / 4096
        // A model whose overhead covers fewer blocks than inodes cannot be constructed.
        assertThrows<IllegalArgumentException> { FootprintModel(4096, 4 * kib) }
    }

    @Test
    fun `block size, overhead alignment and the denominator floor are validated`() {
        assertThrows<IllegalArgumentException> { FootprintModel(512, 24 * kib) }
        assertThrows<IllegalArgumentException> { FootprintModel(4096, 24 * kib + 1) }
        assertThrows<IllegalArgumentException> { FootprintModel(4096, 24 * kib, fragmentationDenominator = 15) }
        assertThrows<IllegalArgumentException> { model.ofObject(-1) }
        assertThrows<IllegalArgumentException> { model.bound(1, -1) }
    }

    @Test
    fun `an overflowing bound fails closed, never wraps`() {
        assertThrows<ArithmeticException> { model.bound(Long.MAX_VALUE - 10, 1) }
        assertThrows<ArithmeticException> { model.bound(0, Long.MAX_VALUE / 1000) }
        assertThrows<ArithmeticException> { model.finalizeBudgetBytes(Int.MAX_VALUE, Int.MAX_VALUE, 15 * mib) }
    }
}
