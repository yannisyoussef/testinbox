package email.testinbox.benchmark

import kotlin.math.ln

/**
 * The `--focused` A′ report (#124 6684712608), written as `FOCUSED.md` beside the
 * evidence: per scenario the T1 lock hold, the T1 statement time and the T1 p99.
 * Never gate evidence. Measured figures, derived figures and the model estimate are
 * labelled apart, and latency is kept apart from throughput and correctness.
 */
object FocusedReport {
    const val FILE = "FOCUSED.md"

    fun render(
        results: List<ScenarioResult>,
        options: BenchmarkOptions,
    ): String =
        buildString {
            appendLine("# A′ focused measurement (NOT gate evidence)")
            appendLine()
            appendLine(
                "CHOSEN only; rates ${options.rates}; concurrency ${options.concurrency}; workspaces ${options.workspaces} " +
                    "with ${if (options.cohortShares) Seeder.PARTICIPANTS + 1 else 0} cohort shares; ${options.measuredSeconds} s " +
                    "measured after ${options.warmupSeconds} s warm-up.",
            )
            appendLine()
            appendLine("Hold: ${Evidence.HOLD_DEFINITION}")
            appendLine()
            appendLine("## Latency [measured]")
            appendLine()
            appendLine(
                "| Scenario | Hold mean | Hold p50 / p95 / p99 | Snapshot (client) mean / p50 / p99 | Snapshot exec (server) mean | " +
                    "Insert p50 / p99 | Commit p50 / p99 | T1 tx p50 / p99 | T1 p50 / p95 / p99 |",
            )
            appendLine("|---|---:|---|---|---:|---|---|---|---|")
            results.forEach { s ->
                val h = s.hold
                appendLine(
                    "| ${s.name} | ${h?.let { ms(it.meanHoldMs) } ?: "-"} | ${three(h?.hold)} | " +
                        "${h?.meanSnapshotMs?.let(
                            ::ms,
                        ) ?: "-"} / ${two(h?.snapshot)} | ${s.t1Statement?.meanExecMs?.let(::ms) ?: "n/a"} | " +
                        "${two(h?.insert)} | ${two(h?.commit)} | ${two(s.t1Transaction)} | ${three(s.t1)} |",
                )
            }
            appendLine()
            appendLine(
                "Snapshot exec (server) is pg_stat_statements' mean execution time of the T1 statement over the measured phase " +
                    "(n/a when the server does not preload pg_stat_statements).",
            )
            appendLine()
            appendLine("## Throughput and correctness [measured]")
            appendLine()
            appendLine(
                "| Scenario | Offered/s | Achieved/s | Completed / scheduled | Terminal lag ms | lock_timeout | Deadlocks | Other errors |",
            )
            appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            results.forEach { s ->
                appendLine(
                    "| ${s.name} | ${ms(s.offeredRate)} | ${ms(s.achievedRate)} | ${s.completedEvents} / ${s.scheduledEvents} | " +
                        "${ms(s.terminalLagMs)} | ${s.lockTimeouts} | ${s.deadlocks} | ${s.otherErrors} |",
                )
            }
            appendLine()
            appendLine("## Lock utilisation [derived] and T1 p99 under M/M/1 [estimate]")
            appendLine()
            appendLine(
                "ρ = offered rate × mean hold. The M/M/1 tail of the lock wait, P(W > t) = ρ·e^(−(1−ρ)t / mean hold), gives " +
                    "the wait's p99 as ln(100ρ) × mean hold / (1 − ρ); adding the measured hold p99 bounds T1 p99 from above " +
                    "(the pre-lock statements are left out). An estimate, NOT a result: the measured T1 p99 above is the result.",
            )
            appendLine()
            appendLine("| Scenario | ρ [derived] | Wait p99 [estimate] | Wait p99 + hold p99 [estimate] |")
            appendLine("|---|---:|---:|---:|")
            results.forEach { s ->
                val h = s.hold ?: return@forEach
                val rho = s.offeredRate * h.meanHoldMs / MILLIS
                val wait = waitP99Ms(rho, h.meanHoldMs)
                appendLine(
                    "| ${s.name} | ${"%.3f".format(rho)} | ${wait?.let(::ms) ?: "unstable (ρ ≥ 1)"} | " +
                        "${wait?.let { ms(it + h.hold.p99Ms) } ?: "-"} |",
                )
            }
            appendLine()
            appendLine(
                "Checkpoint rule (owner decision 2026-11-06): continue if the median hold is <= 7 ms and the projected p99 " +
                    "<= 50 ms; escalate if > 8 ms, or if the reductions are under half of their estimates.",
            )
            results.filter { it.planAtEnd != null }.forEach { s ->
                appendLine()
                appendLine("## ${s.name}: T1's generic plan from the statistics at the end of the measured phase [diagnostic]")
                appendLine()
                appendLine("```")
                appendLine(s.planAtEnd)
                appendLine("```")
            }
        }

    /** M/M/1: the p99 of the queueing wait, or null when the queue is unstable. 0 when fewer than 1 % of arrivals wait. */
    fun waitP99Ms(
        rho: Double,
        meanServiceMs: Double,
    ): Double? {
        if (rho >= 1.0) return null
        if (rho <= P99_TAIL) return 0.0
        return ln(rho / P99_TAIL) * meanServiceMs / (1.0 - rho)
    }

    private const val P99_TAIL = 0.01
    private const val MILLIS = 1_000.0

    private fun ms(v: Double): String = "%.2f".format(v)

    private fun two(l: LatencySummary?): String = l?.let { "${ms(it.p50Ms)} / ${ms(it.p99Ms)}" } ?: "-"

    private fun three(l: LatencySummary?): String = l?.let { "${ms(it.p50Ms)} / ${ms(it.p95Ms)} / ${ms(it.p99Ms)}" } ?: "-"
}
