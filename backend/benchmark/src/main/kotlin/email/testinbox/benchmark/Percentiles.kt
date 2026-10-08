package email.testinbox.benchmark

/**
 * Nearest-rank percentiles (the `pgbench -l` convention ADR-035 §11's
 * evidence was computed with): the value at rank `ceil(p × N)` of the sorted
 * sample, 1-based. No interpolation, so p99 is always an observed latency,
 * never a figure between two of them.
 */
object Percentiles {
    fun nearestRank(
        sorted: LongArray,
        percentile: Double,
    ): Long {
        require(sorted.isNotEmpty()) { "a percentile of an empty sample is undefined" }
        require(percentile > 0.0 && percentile <= 100.0) { "percentile must be in (0, 100], was $percentile" }
        val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt()
        return sorted[rank.coerceIn(1, sorted.size) - 1]
    }
}

/** p50 / p95 / p99 / max of one latency series, in milliseconds. */
data class LatencySummary(
    val samples: Int,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
) {
    companion object {
        /** Null for an empty series: a missing figure must stay visibly missing, never read as zero. */
        fun ofNanos(nanos: Collection<Long>): LatencySummary? {
            if (nanos.isEmpty()) return null
            val sorted = nanos.toLongArray().also { it.sort() }

            fun pct(p: Double) = Percentiles.nearestRank(sorted, p) / NANOS_PER_MILLI
            return LatencySummary(sorted.size, pct(50.0), pct(95.0), pct(99.0), sorted.last() / NANOS_PER_MILLI)
        }

        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
