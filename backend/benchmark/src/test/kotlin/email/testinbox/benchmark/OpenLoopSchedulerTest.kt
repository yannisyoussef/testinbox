package email.testinbox.benchmark

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class OpenLoopSchedulerTest {
    @Test
    fun `latency is measured from the due time, so a delay before completion is inside it`() {
        val report =
            OpenLoopScheduler(ratePerSecond = 1000.0, arrivals = Arrivals.UNIFORM).run(count = 1, workers = 1) {
                Thread.sleep(100)
                true
            }
        report.completed shouldBe 1
        report.latencyNanos shouldHaveSize 1
        report.latencyNanos.single() shouldBeGreaterThanOrEqual TimeUnit.MILLISECONDS.toNanos(100)
    }

    @Test
    fun `an event due while every worker is busy carries its queue wait as schedule lag`() {
        // Two events due 1 ms apart, one worker: the second waits for the first's 100 ms.
        val report =
            OpenLoopScheduler(ratePerSecond = 1000.0, arrivals = Arrivals.UNIFORM).run(count = 2, workers = 1) { event ->
                if (event.index == 0) Thread.sleep(100)
                true
            }
        report.completed shouldBe 2
        // Latencies are in schedule order (index), so the second entry is the queued event.
        report.latencyNanos[1] shouldBeGreaterThanOrEqual TimeUnit.MILLISECONDS.toNanos(95)
    }

    @Test
    fun `the schedule is open loop and deterministic for a seed`() {
        val uniform = OpenLoopScheduler(100.0, Arrivals.UNIFORM, seed = 1).schedule(5)
        uniform.toList() shouldBe listOf(0L, 10_000_000L, 20_000_000L, 30_000_000L, 40_000_000L)

        val a = OpenLoopScheduler(500.0, Arrivals.POISSON, seed = 7).schedule(1000)
        val b = OpenLoopScheduler(500.0, Arrivals.POISSON, seed = 7).schedule(1000)
        a.toList() shouldBe b.toList()
        a.toList().zipWithNext().all { (x, y) -> y >= x } shouldBe true
        // Normalised: the last due time is exactly the nominal span, so the offered rate is the nominal one.
        a.first() shouldBe 0L
        a.last() shouldBe 999 * 2_000_000L
        // The gaps keep an exponential shape: many short, a few long.
        val gapsMs = a.toList().zipWithNext { x, y -> (y - x) / 1e6 }
        gapsMs.count { it < 1.0 } shouldBeGreaterThan 300
        gapsMs.max() shouldBeGreaterThan 8.0
        OpenLoopScheduler(500.0, Arrivals.POISSON, seed = 3).schedule(1).toList() shouldBe listOf(0L)
        OpenLoopScheduler(500.0, Arrivals.POISSON, seed = 3).schedule(0).size shouldBe 0
    }

    @Test
    fun `a failing task counts as failed and contributes no latency`() {
        val report =
            OpenLoopScheduler(1000.0, Arrivals.UNIFORM).run(count = 4, workers = 2) { event ->
                if (event.index % 2 == 0) error("boom") else true
            }
        report.scheduled shouldBe 4
        report.completed shouldBe 2
        report.failed shouldBe 2
        report.latencyNanos shouldHaveSize 2
    }
}
