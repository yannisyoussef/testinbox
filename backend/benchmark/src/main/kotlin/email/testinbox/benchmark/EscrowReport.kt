package email.testinbox.benchmark

import java.util.Locale

/**
 * ESCROW.md: the slot-lane escrow M1 prototype against mode a at the same points
 * (`--escrow-variant`). NON-GATE: never gate evidence, never ADR evidence. Any point
 * above 2R = 62/s is labelled a STRESS DIAGNOSTIC and is not a criterion.
 */
object EscrowReport {
    const val FILE = "ESCROW.md"

    fun render(
        modeA: List<ScenarioResult>,
        escrow: List<ScenarioResult>,
        options: BenchmarkOptions,
    ): String =
        buildString {
            appendLine("# Slot-lane escrow M1 prototype vs mode a — NON-GATE")
            appendLine()
            appendLine(
                "Never gate evidence and never ADR evidence (`adrEvidence: false`). ADR-038 is PROPOSED. Mode a ran first, on the " +
                    "database before V28-proto was applied; the escrow ran after it on the same closed cohort. The escrow's T1 includes " +
                    "the stub `t1_schedule` spend (G1). Gate loads: R = 31 and 2R = 62/s only; anything above is a STRESS DIAGNOSTIC.",
            )
            appendLine()
            appendLine("Pool: ${if (options.coldPool) "cold" else "warm"}, maxLifetime ${options.effectivePoolMaxLifetime}.")
            appendLine()
            appendLine("## T1 (completion − due), per point")
            appendLine()
            appendLine(
                "| point | label | mode | T1 p50 | T1 p99 | T1 tx mean | T1 tx p99 | sustained | lock timeouts | deadlocks | other errors |",
            )
            appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
            for (e in escrow) {
                val a = modeA.firstOrNull { it.concurrency == e.concurrency && Math.round(it.offeredRate) == Math.round(e.offeredRate) }
                val point = "c${e.concurrency} @ ${Math.round(e.offeredRate)}/s"
                val label = EscrowScenarioReport.label(e.offeredRate)
                if (a != null) appendLine(row(point, label, "mode a", a))
                appendLine(row(point, label, "escrow", e))
            }
            appendLine()
            for (e in escrow) {
                val r = e.escrow ?: continue
                appendLine("## ${e.name} — ${r.label}")
                appendLine()
                appendLine("### Fast-path t1Steps (ms), cold/warm by the lock statement's connection")
                appendLine()
                appendLine("| step | n | p50 | p99 | mean | cold n | cold p50 | cold p99 | warm n | warm p50 | warm p99 |")
                appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
                r.t1Steps.forEach { (name, s) ->
                    appendLine(
                        "| $name | ${s.all?.latency?.samples ?: 0} | ${ms(s.all?.latency?.p50Ms)} | ${ms(s.all?.latency?.p99Ms)} | " +
                            "${ms(s.all?.meanMs)} | ${s.cold?.latency?.samples ?: 0} | ${ms(s.cold?.latency?.p50Ms)} | " +
                            "${ms(s.cold?.latency?.p99Ms)} | ${s.warm?.latency?.samples ?: 0} | ${ms(s.warm?.latency?.p50Ms)} | " +
                            "${ms(s.warm?.latency?.p99Ms)} |",
                    )
                }
                appendLine()
                r.slowT1Steps?.let { slow ->
                    appendLine("### Slow path (escalated events) t1Steps (ms): the drain fence is the lock")
                    appendLine()
                    appendLine("| step | n | p50 | p99 | mean |")
                    appendLine("|---|---:|---:|---:|---:|")
                    slow.steps.forEach { (name, s) ->
                        appendLine("| $name | ${s.latency.samples} | ${ms(s.latency.p50Ms)} | ${ms(s.latency.p99Ms)} | ${ms(s.meanMs)} |")
                    }
                    appendLine()
                }
                appendLine("### Counters")
                appendLine()
                appendLine("- paths: ${r.paths}")
                appendLine("- escalations: ${r.escalations} (rate ${pct(r.escalationRate)})")
                appendLine("- T1 by path (completion − due): fast ${summary(r.t1ByPath["fast"])}; slow ${summary(r.t1ByPath["slow"])}")
                appendLine("- inline refills by kind:cause: ${r.inlineRefills}; duration ${summary(r.inlineRefillDuration)}")
                appendLine("- scope-row lock statement: ${summary(r.scopeLock)}; waits >= 1 ms: ${r.scopeLockWaitsOver1ms}")
                appendLine(
                    "- regrants: ${r.regrants} (not granted: ${r.regrantsNotGranted}); durations " +
                        r.regrantDuration.entries.joinToString { "${it.key} ${summary(it.value)}" },
                )
                appendLine("- background scope refills (keeper): ${r.backgroundScopeRefills}; keeper ${r.keeper}")
                appendLine(
                    "- stub t1_schedule (one token per T1 attempt, OD-5): ${r.scheduleSpends} spends, " +
                        "${String.format(Locale.ROOT, "%.4f", r.scheduleSpendsPerEvent)} per event (escalation rate " +
                        "${pct(r.escalationRate)}); per-event spend time ${summary(r.schedule)}",
                )
                appendLine("- synthetic lane-epoch bumps in the phase: ${r.laneEpochBumps}")
                appendLine("- settings and end-of-scenario audit: ${r.settings}")
                appendLine()
            }
        }

    private fun row(
        point: String,
        label: String,
        mode: String,
        r: ScenarioResult,
    ): String =
        "| $point | ${if (label.startsWith("STRESS")) "STRESS DIAGNOSTIC" else "NON-GATE"} | $mode | ${ms(r.t1?.p50Ms)} | " +
            "${ms(r.t1?.p99Ms)} | ${ms(r.t1TransactionMeanMs)} | ${ms(r.t1Transaction?.p99Ms)} | ${ratio(r)} | ${r.lockTimeouts} | " +
            "${r.deadlocks} | ${r.otherErrors} |"

    private fun ratio(r: ScenarioResult): String =
        if (r.offeredRate ==
            0.0
        ) {
            "–"
        } else {
            String.format(Locale.ROOT, "%.3f", r.achievedRate / r.offeredRate)
        }

    private fun ms(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "–"

    private fun pct(v: Double): String = String.format(Locale.ROOT, "%.2f %%", 100 * v)

    private fun summary(s: LatencySummary?): String =
        s?.let { "n=${it.samples} p50 ${ms(it.p50Ms)} p99 ${ms(it.p99Ms)} max ${ms(it.maxMs)} ms" } ?: "none"
}
