package email.testinbox.domain.storage

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * ADR-035 §9 / §18 gate 8: `Q ≥ G + max(1 GiB, 10 %, H + measured lag churn)`.
 *
 * The interpretation this repository fixes: "10 %" is ten per cent OF G,
 * `floor(G / 10)`, so the margin is read against the same base as its other
 * two terms. Each test below states which term is the maximum.
 */
class BucketQuotaFuseTest {
    private val gib = BucketQuotaFuse.GIB
    private val mib = 1024L * 1024

    @Test
    fun `the owner's planning values satisfy the fuse - 40 GiB under a 50 GiB quota`() {
        // 10 % of G = 4 GiB is the maximum term: 40 + 4 = 44 GiB ≤ 50 GiB.
        BucketQuotaFuse.minimumQuotaBytes(40 * gib, 240 * mib, 0) shouldBe 44 * gib
        BucketQuotaFuse.holds(50 * gib, 40 * gib, 240 * mib, 0) shouldBe true
    }

    @Test
    fun `exact boundary, one byte below, one byte above`() {
        val minimum = BucketQuotaFuse.minimumQuotaBytes(40 * gib, 240 * mib, 0)
        BucketQuotaFuse.holds(minimum, 40 * gib, 240 * mib, 0) shouldBe true
        BucketQuotaFuse.holds(minimum - 1, 40 * gib, 240 * mib, 0) shouldBe false
        BucketQuotaFuse.holds(minimum + 1, 40 * gib, 240 * mib, 0) shouldBe true
    }

    @Test
    fun `the 1 GiB floor wins for a small G`() {
        // G = 4 GiB: 10 % is 0.4 GiB, H + churn is 240 MiB; 1 GiB is the maximum.
        BucketQuotaFuse.minimumQuotaBytes(4 * gib, 240 * mib, 0) shouldBe 5 * gib
    }

    @Test
    fun `ten per cent is ten per cent of G, floored`() {
        // G = 25 GiB + 7 bytes: floor(G / 10) = 2.5 GiB (the 7 bytes floor away), the maximum term.
        val g = 25 * gib + 7
        BucketQuotaFuse.minimumQuotaBytes(g, 240 * mib, 0) shouldBe g + g / 10
        (g / 10) shouldBe (25 * gib) / 10
    }

    @Test
    fun `a large H plus the measured churn wins over both other terms`() {
        // H = 8 processes × 16 × 15 MiB = 1920 MiB; churn 3 GiB: 4.875 GiB > 10 % of 40 GiB = 4 GiB > 1 GiB.
        val h = 8L * 16 * 15 * mib
        val churn = 3 * gib
        BucketQuotaFuse.minimumQuotaBytes(40 * gib, h, churn) shouldBe 40 * gib + h + churn
        BucketQuotaFuse.holds(44 * gib, 40 * gib, h, churn) shouldBe false
        BucketQuotaFuse.holds(45 * gib, 40 * gib, h, churn) shouldBe true
    }

    @Test
    fun `the measured churn alone can tip the fuse`() {
        val h = 240 * mib
        // Without churn 10 % of G (4 GiB) is the maximum; with 4 GiB of churn, H + churn (4.23 GiB) is.
        BucketQuotaFuse.minimumQuotaBytes(40 * gib, h, 0) shouldBe 44 * gib
        BucketQuotaFuse.minimumQuotaBytes(40 * gib, h, 4 * gib) shouldBe 40 * gib + h + 4 * gib
    }

    @Test
    fun `arithmetic overflow is a failure, never a wrapped margin`() {
        assertThrows<ArithmeticException> { BucketQuotaFuse.minimumQuotaBytes(Long.MAX_VALUE - 10, 240 * mib, 0) }
        assertThrows<ArithmeticException> { BucketQuotaFuse.minimumQuotaBytes(40 * gib, Long.MAX_VALUE, 1) }
        assertThrows<ArithmeticException> { BucketQuotaFuse.holds(Long.MAX_VALUE, Long.MAX_VALUE - 10, 0, 0) }
    }

    @Test
    fun `non-positive G and negative H or churn are refused as inputs`() {
        assertThrows<IllegalArgumentException> { BucketQuotaFuse.minimumQuotaBytes(0, 0, 0) }
        assertThrows<IllegalArgumentException> { BucketQuotaFuse.minimumQuotaBytes(gib, -1, 0) }
        assertThrows<IllegalArgumentException> { BucketQuotaFuse.minimumQuotaBytes(gib, 0, -1) }
    }
}
