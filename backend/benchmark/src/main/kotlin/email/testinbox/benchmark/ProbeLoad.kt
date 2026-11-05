package email.testinbox.benchmark

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** What one probe event saw. */
enum class ProbeOutcome {
    /** The event completed (its copies admitted, refused or rate-limited: all `250` on the wire). */
    DELIVERED,

    /** The whole DATA would be answered `451` (a `StorageUnavailableException`). */
    UNAVAILABLE,

    /** No candidate survived resolution: nothing reaches T1 (an unknown recipient). */
    NO_T1,
}

/** One probe class's distribution, reported under `variant` (timing-channel evidence, never gate D). */
data class ProbeSummary(
    val events: Int,
    /** Due time to completion, so schedule lag and every lock wait are inside. */
    val latency: LatencySummary?,
    /** UNAVAILABLE / events. */
    val unavailableRate: Double,
    /** False when no event of the class ever reached T1 (the unknown-recipient class). */
    val reachesT1: Boolean,
)

/**
 * The timing-channel probes of security review S-C1 (#136): while the scenario
 * floods one hot workspace, each class sends a slow open-loop stream of its own
 * events and records the latency and the `451` rate of each.
 *
 * - `inside`: one inbox of the HOT workspace co-addressed in one event with the
 *   observer's own inbox in another workspace — the tenant-observer attack: the
 *   observer reads its own inbox's outcome and timing;
 * - `cold`: one inbox of a cold workspace;
 * - `unknown`: an unknown recipient; resolution discards it, so it never reaches
 *   T1 and its latency is the harness's own (recorded as `reachesT1 = false`).
 */
class ProbeLoad(
    private val classes: Map<String, List<SeededInbox>>,
    private val ratePerSecond: Double,
    private val durationSeconds: Int,
    private val seed: Long,
    private val ingest: (List<SeededInbox>) -> ProbeOutcome,
) {
    private class Series {
        val latencies: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val unavailable = AtomicInteger()
        val reachedT1 = AtomicInteger()
    }

    private val series = classes.keys.associateWith { Series() }
    private val threads =
        classes.entries.mapIndexed { index, (name, inboxes) ->
            Thread({ drive(inboxes, series.getValue(name), seed + index) }, "bench-probe-$name").apply { isDaemon = true }
        }

    fun start() = threads.forEach(Thread::start)

    fun join() = threads.forEach(Thread::join)

    private fun drive(
        inboxes: List<SeededInbox>,
        into: Series,
        seed: Long,
    ) {
        val events = Math.ceil(ratePerSecond * durationSeconds).toInt()
        OpenLoopScheduler(ratePerSecond, Arrivals.POISSON, seed).run(events, 1) { due ->
            val outcome = if (inboxes.isEmpty()) ProbeOutcome.NO_T1 else ingest(inboxes)
            into.latencies += System.nanoTime() - due.scheduledNanos
            if (outcome == ProbeOutcome.UNAVAILABLE) into.unavailable.incrementAndGet()
            if (outcome != ProbeOutcome.NO_T1) into.reachedT1.incrementAndGet()
            outcome != ProbeOutcome.UNAVAILABLE
        }
    }

    fun summaries(): Map<String, ProbeSummary> =
        series.mapValues { (_, s) ->
            val events = s.latencies.size
            ProbeSummary(
                events = events,
                latency = LatencySummary.ofNanos(s.latencies),
                unavailableRate = if (events == 0) 0.0 else s.unavailable.get().toDouble() / events,
                reachesT1 = s.reachedT1.get() > 0,
            )
        }
}
