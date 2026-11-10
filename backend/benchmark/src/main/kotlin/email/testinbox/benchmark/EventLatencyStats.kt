package email.testinbox.benchmark

/** One completed event as the scenario summary reads it: its due time and what the protocol recorded. */
data class TimedEvent(
    val dueNanos: Long,
    val timings: EventTimings,
)

/** The per-phase latency summaries of a scenario's completed events. */
data class EventLatencies(
    val t1: LatencySummary?,
    val t1Transaction: LatencySummary?,
    val t2: LatencySummary?,
    val lockWait: LatencySummary?,
    val slotWait: LatencySummary?,
    /** Completed events that never reached T1 (every copy discarded by INGEST, a `250`): in no latency above. */
    val withoutT1: Int,
)

/**
 * The summary's one rule for latencies: only events whose T1 actually ran enter
 * them, and every latency is a difference of two `System.nanoTime` readings that
 * were both taken. `TimingAdmissionStore` sets `t1EndNanos` (and `t1Nanos`) from
 * `System.nanoTime` when T1 ends; the open-loop scheduler's `dueNanos` is
 * `System.nanoTime`-based too. An event whose every copy INGEST discarded completes
 * (a `250`) with `t1EndNanos` unset (0): before this rule its `0 - dueNanos`, minus
 * the host's uptime, entered the T1 statistics (staging, #136 at 1023af5).
 */
internal object EventLatencyStats {
    fun of(events: List<TimedEvent>): EventLatencies {
        val timed = events.filter { it.timings.t1EndNanos != UNSET }
        timed.forEach {
            check(it.timings.t1EndNanos >= it.dueNanos && it.timings.t1Nanos >= 0) {
                "T1 ended before its due time (t1EndNanos ${it.timings.t1EndNanos}, dueNanos ${it.dueNanos}): two clocks, or an unset timestamp"
            }
        }
        return EventLatencies(
            t1 = LatencySummary.ofNanos(timed.map { it.timings.t1EndNanos - it.dueNanos }),
            t1Transaction = LatencySummary.ofNanos(timed.map { it.timings.t1Nanos }),
            t2 = LatencySummary.ofNanos(timed.map { it.timings.t2Nanos }),
            lockWait = LatencySummary.ofNanos(timed.map { it.timings.lockWaitNanos }),
            slotWait = LatencySummary.ofNanos(timed.map { it.timings.slotWaitNanos }),
            withoutT1 = events.size - timed.size,
        )
    }

    /** `EventTimings`' initial value: no T1 has ended. */
    private const val UNSET = 0L
}
