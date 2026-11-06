package email.testinbox.benchmark

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** What one probe event saw. */
enum class ProbeOutcome {
    /** The event completed (its copies admitted, refused or rate-limited: all `250` on the wire). */
    DELIVERED,

    /** The whole DATA would be answered `451` (a `StorageUnavailableException`). */
    UNAVAILABLE,

    /** No candidate survived resolution and INGEST: nothing reaches the guarded protocol. */
    NO_T1,
}

/**
 * One probe event's result. [targetReached]: the class's TARGET copy (the first
 * recipient of its event: the hot workspace's inbox for `inside`, the cold
 * workspace's for `cold`) survived resolution and ADR-027 INGEST and was handed to
 * the guarded protocol - its workspace's write slot, then T1. Only those events
 * measure the target workspace; an `inside` event whose hot copy was rate-limited
 * reaches T1 with the observer's copy alone and measures nothing about the hot one.
 */
data class ProbeResult(
    val outcome: ProbeOutcome,
    val targetReached: Boolean,
)

/** One probe class's distribution, reported under `variant` (timing-channel evidence, never gate D). */
data class ProbeSummary(
    /** Every event the class sent. */
    val events: Int,
    /** Events whose target copy reached the guarded protocol; the statistics below cover only these. */
    val targetReachedEvents: Int,
    /** Due time to completion over the target-reached events: schedule lag and every lock wait inside. */
    val latency: LatencySummary?,
    /** UNAVAILABLE / target-reached events. */
    val unavailableRate: Double,
    /** False when no event of the class ever reached the guarded protocol (the unknown-recipient class). */
    val reachesT1: Boolean,
    /** The target-reached latencies, for pooling across repetitions; never written to the evidence. */
    val targetLatencyNanos: List<Long> = emptyList(),
    val targetUnavailable: Int = 0,
)

/**
 * The timing-channel probes of security review S-C1 (#136): while the scenario
 * floods one hot workspace, each class sends a slow open-loop stream of events
 * (its recipient sets in rotation) and records, per event, its latency, whether
 * it was answered `451`, and whether its TARGET copy reached the guarded
 * protocol ([ProbeResult]).
 *
 * - `inside`: one of the hot workspace's reserved inboxes (the target)
 *   co-addressed in one event with the observer's own inbox in another
 *   workspace - the tenant-observer attack: the observer reads its own inbox's
 *   outcome and timing;
 * - `cold`: one inbox of a cold workspace (the target);
 * - `unknown`: an unknown recipient; resolution discards it, so it never reaches
 *   T1 and its latency is the harness's own (recorded as `reachesT1 = false`).
 */
class ProbeLoad(
    private val classes: Map<String, List<List<SeededInbox>>>,
    private val ratePerSecond: Double,
    private val durationSeconds: Int,
    private val seed: Long,
    private val ingest: (List<SeededInbox>) -> ProbeResult,
) {
    private class Series {
        val events = AtomicInteger()
        val latencies: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val unavailable = AtomicInteger()
        val reachedT1 = AtomicInteger()
    }

    private val series = classes.keys.associateWith { Series() }
    private val threads =
        classes.entries.mapIndexed { index, (name, sets) ->
            Thread({ drive(sets, series.getValue(name), seed + index) }, "bench-probe-$name").apply { isDaemon = true }
        }

    fun start() = threads.forEach(Thread::start)

    fun join() = threads.forEach(Thread::join)

    private fun drive(
        sets: List<List<SeededInbox>>,
        into: Series,
        seed: Long,
    ) {
        val events = Math.ceil(ratePerSecond * durationSeconds).toInt()
        val next = AtomicInteger()
        OpenLoopScheduler(ratePerSecond, Arrivals.POISSON, seed).run(events, 1) { due ->
            val inboxes = sets[next.getAndIncrement() % sets.size]
            val result = if (inboxes.isEmpty()) ProbeResult(ProbeOutcome.NO_T1, false) else ingest(inboxes)
            into.events.incrementAndGet()
            if (result.targetReached) {
                into.latencies += System.nanoTime() - due.scheduledNanos
                into.reachedT1.incrementAndGet()
                if (result.outcome == ProbeOutcome.UNAVAILABLE) into.unavailable.incrementAndGet()
            }
            result.outcome != ProbeOutcome.UNAVAILABLE
        }
    }

    fun summaries(): Map<String, ProbeSummary> =
        series.mapValues { (_, s) ->
            val reached = s.reachedT1.get()
            ProbeSummary(
                events = s.events.get(),
                targetReachedEvents = reached,
                latency = LatencySummary.ofNanos(s.latencies),
                unavailableRate = if (reached == 0) 0.0 else s.unavailable.get().toDouble() / reached,
                reachesT1 = reached > 0,
                targetLatencyNanos = s.latencies.toList(),
                targetUnavailable = s.unavailable.get(),
            )
        }
}

/**
 * The probe scenarios' figures pooled over their repetitions, per load point and
 * mode (security review S-C1 (c)): the sample each p99 rests on, and whether it
 * reaches [MIN_TARGET_EVENTS]. Reported under `variant` only; no verdict - the
 * criterion is a PROPOSED recommendation (ADR-035 PROPOSED note), not adopted.
 */
object ProbePooling {
    /**
     * The smallest pooled sample of target-reached events a class needs before its
     * p99 is read: at 300 the nearest-rank p99 is the 297th value, so three
     * observations lie above it and one outlier cannot set it.
     */
    const val MIN_TARGET_EVENTS = 300

    fun pool(results: List<ScenarioResult>): List<Map<String, Any?>> =
        results
            .filter { it.probes != null }
            .groupBy { (if (it.name.contains("-probe-paced")) "paced" else "heaviest") to it.mode }
            .map { (key, runs) ->
                val classes = runs.flatMap { it.probes.orEmpty().entries }.groupBy({ it.key }, { it.value }).mapValues { pooled(it.value) }
                val inside = classes["inside"]?.get("latency") as LatencySummary?
                val cold = classes["cold"]?.get("latency") as LatencySummary?
                linkedMapOf(
                    "point" to key.first,
                    "mode" to key.second.name,
                    "repetitions" to runs.size,
                    "classes" to classes,
                    "insideMinusColdP99Ms" to if (inside != null && cold != null) inside.p99Ms - cold.p99Ms else null,
                )
            }

    private fun pooled(summaries: List<ProbeSummary>): Map<String, Any?> {
        val reached = summaries.sumOf { it.targetReachedEvents }
        return linkedMapOf(
            "events" to summaries.sumOf { it.events },
            "targetReachedEvents" to reached,
            "minimumSampleMet" to (reached >= MIN_TARGET_EVENTS),
            "latency" to LatencySummary.ofNanos(summaries.flatMap { it.targetLatencyNanos }),
            "unavailableRate" to if (reached == 0) null else summaries.sumOf { it.targetUnavailable }.toDouble() / reached,
        )
    }
}
