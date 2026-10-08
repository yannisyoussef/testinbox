package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PercentilesTest {
    private val sample = longArrayOf(15, 20, 35, 40, 50)

    @Test
    fun `nearest rank on the textbook sample`() {
        // ceil(0.40 × 5) = 2nd → 20; ceil(0.50 × 5) = 3rd → 35; ceil(0.95 × 5) = 5th → 50.
        Percentiles.nearestRank(sample, 40.0) shouldBe 20
        Percentiles.nearestRank(sample, 50.0) shouldBe 35
        Percentiles.nearestRank(sample, 95.0) shouldBe 50
        Percentiles.nearestRank(sample, 99.0) shouldBe 50
        Percentiles.nearestRank(sample, 100.0) shouldBe 50
    }

    @Test
    fun `a percentile is always an observed value, never interpolated`() {
        val sorted = LongArray(100) { (it + 1) * 10L }
        Percentiles.nearestRank(sorted, 99.0) shouldBe 990
        Percentiles.nearestRank(sorted, 50.0) shouldBe 500
        Percentiles.nearestRank(longArrayOf(7), 99.0) shouldBe 7
    }

    @Test
    fun `empty and out-of-range inputs are refused`() {
        shouldThrow<IllegalArgumentException> { Percentiles.nearestRank(longArrayOf(), 50.0) }
        shouldThrow<IllegalArgumentException> { Percentiles.nearestRank(sample, 0.0) }
        shouldThrow<IllegalArgumentException> { Percentiles.nearestRank(sample, 101.0) }
    }

    @Test
    fun `a latency summary converts nanoseconds to milliseconds and is null for no samples`() {
        LatencySummary.ofNanos(emptyList()) shouldBe null
        val summary = LatencySummary.ofNanos(listOf(15_000_000L, 20_000_000L, 35_000_000L, 40_000_000L, 50_000_000L))!!
        summary.samples shouldBe 5
        summary.p50Ms shouldBe 35.0
        summary.p95Ms shouldBe 50.0
        summary.p99Ms shouldBe 50.0
        summary.maxMs shouldBe 50.0
    }
}
