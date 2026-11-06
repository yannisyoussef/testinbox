package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Staging run of #136 at 1023af5 printed `T1 p99 -1.53E9 ms` for a probe run:
 * flood events whose every copy INGEST discarded completed without T1, so their
 * unset `t1EndNanos` (0) minus a `System.nanoTime` due time (the host's uptime)
 * entered the T1 statistics. A latency is only ever a difference of two
 * `System.nanoTime` readings that were both taken, never negative.
 */
class EventLatencyStatsTest {
    private val ms = 1_000_000L

    private fun reached(
        due: Long,
        t1Ms: Long,
    ) = TimedEvent(
        due,
        EventTimings().apply {
            t1Nanos = t1Ms * ms
            t1EndNanos = due + t1Ms * ms
            t2Nanos = ms
        },
    )

    /** A flood event whose copies were all INGEST-discarded: no T1 ran, nothing was timed. */
    private fun discarded(due: Long) = TimedEvent(due, EventTimings())

    @Test
    fun `events that never reached T1 enter no latency, and no latency is negative or absurd`() {
        val uptime = 1_530_989_770_067_168_000L // the staging host's nanoTime, as in the bad line
        val events = List(10) { reached(uptime + it * ms, 5) } + List(990) { discarded(uptime + it * ms) }
        val stats = EventLatencyStats.of(events)
        stats.withoutT1 shouldBe 990
        stats.t1?.samples shouldBe 10
        stats.t1?.p99Ms shouldBe 5.0
        stats.t1Transaction?.p50Ms shouldBe 5.0
        stats.t2?.samples shouldBe 10
        listOfNotNull(stats.t1, stats.t1Transaction, stats.t2, stats.lockWait, stats.slotWait).forEach {
            (it.p50Ms >= 0 && it.maxMs < 3_600_000) shouldBe true
        }
    }

    @Test
    fun `a T1 end before its due time (two clocks, or an unset due) fails the summary instead of entering it`() {
        val bad =
            TimedEvent(
                5_000 * ms,
                EventTimings().apply {
                    t1EndNanos = 10 * ms
                    t1Nanos = ms
                },
            )
        shouldThrow<IllegalStateException> { EventLatencyStats.of(listOf(bad)) }
    }

    @Test
    fun `a negative latency never becomes a summary`() {
        shouldThrow<IllegalArgumentException> { LatencySummary.ofNanos(listOf(5 * ms, -1)) }
    }
}
