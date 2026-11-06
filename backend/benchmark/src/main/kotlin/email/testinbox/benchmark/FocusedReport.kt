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
            val population =
                if (options.focusedCohort) {
                    "the beta's production state: the closed cohort of ${Seeder.PARTICIPANTS + 1} workspaces (30 participants + 1 " +
                        "legacy), its pool reserved, approved and REQUIRED, every event to those workspaces"
                } else {
                    "workspaces ${options.workspaces} with ${if (options.cohortShares) Seeder.PARTICIPANTS + 1 else 0} cohort shares"
                }
            appendLine(
                "CHOSEN (mode a) only; rates ${options.rates}; concurrency ${options.concurrency}; $population; " +
                    "${options.measuredSeconds} s measured after ${options.warmupSeconds} s warm-up.",
            )
            appendLine()
            appendLine("Steps: ${Evidence.STEPS_DEFINITION}")
            appendLine()
            appendLine("## T1 step by step [measured], ms: mean / p50 / p95 / p99")
            appendLine()
            appendLine("| Step | " + results.joinToString(" | ") { it.name } + " |")
            appendLine("|---|" + results.joinToString("") { "---|" })
            T1StepsSummary.NAMES.forEach { step ->
                appendLine(
                    "| $step | " +
                        results.joinToString(" | ") { s ->
                            s.t1Steps
                                ?.steps
                                ?.get(
                                    step,
                                )?.let { "${ms(it.meanMs)} / ${ms(it.latency.p50Ms)} / ${ms(it.latency.p95Ms)} / ${ms(it.latency.p99Ms)}" }
                                ?: "-"
                        } + " |",
                )
            }
            appendLine(
                "| snapshot exec (server, mean) | " +
                    results.joinToString(" | ") { s -> s.t1Statement?.let { "${ms(it.meanExecMs)} (max ${ms(it.maxExecMs)})" } ?: "n/a" } +
                    " |",
            )
            appendLine(
                "| snapshot plans in the phase × mean plan | " +
                    results.joinToString(" | ") { s -> s.t1Statement?.let { "${it.plans} × ${ms(it.meanPlanMs)}" } ?: "n/a" } + " |",
            )
            appendLine("| T1 tx p50 / p99 | " + results.joinToString(" | ") { two(it.t1Transaction) } + " |")
            appendLine("| **T1 p50 / p95 / p99** (completion − due) | " + results.joinToString(" | ") { three(it.t1) } + " |")
            appendLine()
            appendLine(
                "Server figures are pg_stat_statements over the measured phase (n/a when it is not preloaded; plans need " +
                    "pg_stat_statements.track_planning). Every pool connection was warmed past prepareThreshold before the phase, " +
                    "so a plan inside it is an invalidation (autovacuum/analyze), not a cold connection.",
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
                val h = s.t1Steps?.steps?.get("hold") ?: return@forEach
                val rho = s.offeredRate * h.meanMs / MILLIS
                val wait = waitP99Ms(rho, h.meanMs)
                appendLine(
                    "| ${s.name} | ${"%.3f".format(rho)} | ${wait?.let(::ms) ?: "unstable (ρ ≥ 1)"} | " +
                        "${wait?.let { ms(it + h.latency.p99Ms) } ?: "-"} |",
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
