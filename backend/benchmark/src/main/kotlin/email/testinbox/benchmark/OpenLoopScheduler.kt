package email.testinbox.benchmark

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/** How arrivals are spaced. `pgbench --rate` uses Poisson; uniform is the deterministic control. */
enum class Arrivals { UNIFORM, POISSON }

/** One scheduled event: its index and when it was DUE, in the scheduler's nanoTime base. */
data class ScheduledEvent(
    val index: Int,
    val scheduledNanos: Long,
)

/** What one open-loop run observed. Latencies are `completion − scheduled`, so schedule lag is inside them. */
data class OpenLoopReport(
    val scheduled: Int,
    val completed: Int,
    val failed: Int,
    /** From the first scheduled instant to the last completion. */
    val elapsedNanos: Long,
    val latencyNanos: List<Long>,
) {
    val achievedPerSecond: Double get() = if (elapsedNanos <= 0) 0.0 else completed / (elapsedNanos / 1e9)
}

/**
 * ADR-035 §11's open loop: events are DUE at the offered rate whether or not
 * earlier ones have finished. A worker pool of fixed size runs them; when
 * every worker is busy a due event waits in the queue, and that wait is part
 * of its latency, exactly as `pgbench --rate` reports it. A closed loop would
 * hide saturation by slowing its own arrivals.
 *
 * The schedule is computed up front from a seed, so two runs at one rate
 * issue identical arrival times.
 */
class OpenLoopScheduler(
    private val ratePerSecond: Double,
    private val arrivals: Arrivals = Arrivals.POISSON,
    private val seed: Long = 0,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(ratePerSecond > 0) { "the offered rate must be positive, was $ratePerSecond" }
    }

    /**
     * Due offsets from the start, nanoseconds, non-decreasing. Poisson gaps
     * are scaled so the LAST due time is exactly `(count − 1) / rate`: a
     * fixed-count Poisson schedule is otherwise random in total length (about
     * 4.5 % at 500 events), and "achieved versus offered" would measure that
     * randomness instead of the system. The gaps keep their exponential shape.
     */
    fun schedule(count: Int): LongArray {
        require(count >= 0)
        if (count == 0) return LongArray(0)
        val random = Random(seed)
        val meanGapNanos = 1e9 / ratePerSecond
        val gaps =
            DoubleArray(count - 1) {
                when (arrivals) {
                    Arrivals.UNIFORM -> meanGapNanos

                    // Exponential inter-arrival: -ln(U) × mean, U in (0, 1].
                    Arrivals.POISSON -> -Math.log(1.0 - random.nextDouble()) * meanGapNanos
                }
            }
        val nominalSpan = meanGapNanos * (count - 1)
        val actualSpan = gaps.sum()
        val scale = if (actualSpan > 0) nominalSpan / actualSpan else 1.0
        val offsets = LongArray(count)
        var t = 0.0
        for (i in 1 until count) {
            t += gaps[i - 1] * scale
            offsets[i] = Math.round(t)
        }
        if (count > 1) offsets[count - 1] = Math.round(nominalSpan)
        return offsets
    }

    /**
     * Runs [count] events on [workers] threads. [task] receives the due event
     * and returns true on success; a throw counts as a failure. Blocks until
     * every event has ended.
     */
    fun run(
        count: Int,
        workers: Int,
        task: (ScheduledEvent) -> Boolean,
    ): OpenLoopReport {
        require(workers > 0) { "at least one worker" }
        val offsets = schedule(count)
        val latencies = LongArray(count)
        val outcomes = IntArray(count) // 0 pending, 1 ok, 2 failed
        val completed = AtomicInteger()
        val failed = AtomicInteger()
        val pool = ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val start = nanoTime()
        var lastCompletion = start
        val lastCompletionLock = Any()
        try {
            for (i in 0 until count) {
                val due = start + offsets[i]
                sleepUntil(due)
                pool.execute {
                    val ok =
                        try {
                            task(ScheduledEvent(i, due))
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            false
                        }
                    val end = nanoTime()
                    latencies[i] = end - due
                    outcomes[i] = if (ok) 1 else 2
                    if (ok) completed.incrementAndGet() else failed.incrementAndGet()
                    synchronized(lastCompletionLock) { if (end > lastCompletion) lastCompletion = end }
                }
            }
        } finally {
            pool.shutdown()
            check(pool.awaitTermination(DRAIN_MINUTES, TimeUnit.MINUTES)) { "open-loop workers did not drain" }
        }
        val okLatencies = (0 until count).filter { outcomes[it] == 1 }.map { latencies[it] }
        return OpenLoopReport(count, completed.get(), failed.get(), lastCompletion - start, okLatencies)
    }

    private fun sleepUntil(targetNanos: Long) {
        while (true) {
            val remaining = targetNanos - nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(minOf(remaining, MAX_PARK_NANOS))
        }
    }

    private companion object {
        const val MAX_PARK_NANOS = 1_000_000L
        const val DRAIN_MINUTES = 30L
    }
}
