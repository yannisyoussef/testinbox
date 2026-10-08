package email.testinbox.benchmark

enum class Verdict { PASS, FAIL, INCOMPLETE }

/** One §11 pass criterion as evaluated. [observed] is numeric where the criterion is, null when there was no data. */
data class Criterion(
    val name: String,
    val pass: Boolean,
    val observed: Double?,
    val threshold: String,
    val detail: String,
)

data class GateVerdict(
    val verdict: Verdict,
    val criteria: Map<String, Criterion>,
    val incompleteReasons: List<String>,
) {
    companion object {
        const val ADR_REVIEW_REQUIRED = "ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)"
    }
}

/**
 * ADR-035 §11's pass criterion, encoded exactly and nowhere else:
 *
 * - 2× the expected load is sustained (achieved ≥ [SUSTAINED_TOLERANCE] × offered);
 * - T1 p99 ≤ 50 ms;
 * - retention p99 ≤ 2× the no-ceiling reference (mode `c`, [ReferenceMode.NO_LOCK])
 *   at the same offered load;
 * - zero deadlocks;
 * - `lock_timeout` < 0.1 %;
 * - no deadline miss caused by slot queueing;
 *
 * plus "no other error" (listed by §11 as amended) and one harness-level
 * soundness criterion §11 implies: no starved retention tick (a sweep that
 * found nothing is not a retention sample, and a run that ran out of targets
 * has not measured retention at the offered load).
 *
 * The sustained-load and T1 criteria are read on the CHOSEN scenarios at 2×
 * [expectedRate] at the load-gated concurrencies ([LOAD_GATED_CONCURRENCY];
 * ADR-035 §11 as amended 2026-10-08: concurrency 1 is the uncontended
 * diagnostic baseline, measured, reported and required for coverage, but one
 * open-loop worker cannot offer 2× the load). The retention ratio is read on
 * EVERY CHOSEN 2× scenario, concurrency 1 included: it is relative to a
 * reference facing the same offered load, so it is meaningful where the load
 * itself cannot be. The rest are read on every scenario that ran, concurrency
 * 1 included, since a deadlock at 1× is still a deadlock.
 *
 * Coverage decides between a verdict and INCOMPLETE: §11's matrix
 * is concurrency 1/10/25/50/100 at 2×, on a population of at least
 * [requiredWorkspaces] workspaces, each with a NO_LOCK reference at the same
 * point and at least [MIN_T1_SAMPLES] T1 and [MIN_RETENTION_SAMPLES]
 * retention samples. A run whose inputs departed from §11 ([nonAdrReasons],
 * see [AdrConformance]) is INCOMPLETE whatever it measured. Criteria are
 * still computed over whatever ran, so a partial run shows what it would
 * have failed, but a partial run never passes.
 */
class GateEvaluator(
    private val expectedRate: Double = EXPECTED_RATE,
    private val requiredConcurrency: Set<Int> = REQUIRED_CONCURRENCY,
    private val requiredWorkspaces: Int = REQUIRED_WORKSPACES,
    private val loadGatedConcurrency: Set<Int> = LOAD_GATED_CONCURRENCY,
) {
    fun evaluate(
        results: List<ScenarioResult>,
        nonAdrReasons: List<String> = emptyList(),
    ): GateVerdict {
        val twiceRate = 2 * expectedRate
        val chosenAt2x = results.filter { it.mode == AdmissionMode.CHOSEN && isRate(it.offeredRate, twiceRate) }
        // ADR-035 §11 as amended (TI-STORAGE-006b, owner decision 1): concurrency 1 is the
        // uncontended diagnostic baseline. It is measured and reported, and it is required for
        // coverage, but the sustained-load and T1 criteria are read at the load-gated concurrencies only;
        // the retention ratio (relative to a reference at the same offered load) and the integrity
        // criteria are read everywhere.
        val loadGated = chosenAt2x.filter { it.concurrency in loadGatedConcurrency }
        val references = results.filter { it.mode == AdmissionMode.REFERENCE }.associateBy { keyOf(it) }

        val criteria = linkedMapOf<String, Criterion>()
        criteria += sustained(loadGated)
        criteria += t1P99(loadGated)
        criteria += retention(chosenAt2x, references)
        criteria += deadlocks(results)
        criteria += lockTimeoutRate(results.filter { it.mode == AdmissionMode.CHOSEN })
        criteria += slotQueueing(results)
        criteria += otherErrors(results)
        criteria += starvedTicks(results)

        val incomplete = nonAdrReasons.map { "not ADR evidence: $it" } + coverageGaps(chosenAt2x, references)
        val verdict =
            when {
                incomplete.isNotEmpty() -> Verdict.INCOMPLETE
                criteria.values.all { it.pass } -> Verdict.PASS
                else -> Verdict.FAIL
            }
        return GateVerdict(verdict, criteria, incomplete)
    }

    private fun sustained(chosen: List<ScenarioResult>): Pair<String, Criterion> {
        val worst = chosen.minOfOrNull { it.achievedRate / it.offeredRate }
        return SUSTAINED to
            Criterion(
                SUSTAINED,
                pass = worst != null && worst >= SUSTAINED_TOLERANCE,
                observed = worst,
                threshold =
                    "achieved / offered >= $SUSTAINED_TOLERANCE at ${2 * expectedRate} events/s at concurrency " +
                        "${loadGatedConcurrency.sorted()} (concurrency 1 is the uncontended baseline, reported only)",
                detail =
                    if (worst == null) {
                        "no CHOSEN scenario at 2x the expected rate"
                    } else {
                        "worst achieved/offered over ${chosen.size} scenario(s); terminal lag " +
                            chosen.joinToString { "c${it.concurrency}=${"%.0f".format(it.terminalLagMs)} ms" }
                    },
            )
    }

    private fun t1P99(chosen: List<ScenarioResult>): Pair<String, Criterion> {
        val withT1 = chosen.mapNotNull { it.t1?.p99Ms }
        val worst = withT1.maxOrNull()
        return T1_P99 to
            Criterion(
                T1_P99,
                pass = worst != null && withT1.size == chosen.size && worst <= T1_P99_MS,
                observed = worst,
                threshold =
                    "T1 p99 <= $T1_P99_MS ms (completion - due, schedule lag included) at concurrency " +
                        "${loadGatedConcurrency.sorted()}; concurrency 1 is reported only",
                detail = if (worst == null) "no T1 sample" else "worst T1 p99 over ${chosen.size} scenario(s)",
            )
    }

    private fun retention(
        chosen: List<ScenarioResult>,
        references: Map<Triple<Int, Int, Int>, ScenarioResult>,
    ): Pair<String, Criterion> {
        val pairs = chosen.map { it to references[keyOf(it)] }
        val ratios =
            pairs.map { (c, ref) ->
                val own = c.retention?.p99Ms
                val base = ref?.takeIf { it.referenceMode == ReferenceMode.NO_LOCK }?.retention?.p99Ms
                if (own == null || base == null || base <= 0.0) null else own / base
            }
        val complete = ratios.isNotEmpty() && ratios.none { it == null }
        val worst = ratios.filterNotNull().maxOrNull()
        val modes = pairs.mapNotNull { it.second?.referenceMode }.distinct()
        return RETENTION to
            Criterion(
                RETENTION,
                pass = complete && worst != null && worst <= RETENTION_FACTOR,
                observed = worst,
                threshold =
                    "retention p99 <= $RETENTION_FACTOR x the NO_LOCK reference's retention p99 (ADR-035 §11 mode c, no global " +
                        "ceiling) at the same offered load",
                detail =
                    when {
                        chosen.isEmpty() -> {
                            "no CHOSEN scenario at 2x the expected rate"
                        }

                        pairs.any { it.second == null } -> {
                            "a scenario has no reference run"
                        }

                        modes.any { it != ReferenceMode.NO_LOCK } -> {
                            "reference mode ${modes.joinToString()} is not the no-ceiling mode c (NO_LOCK); CEILING_OFF takes the same lock"
                        }

                        !complete -> {
                            "a scenario lacks its own or its reference's retention sample"
                        }

                        else -> {
                            "worst chosen/reference retention p99 ratio over ${chosen.size} scenario(s); reference mode NO_LOCK"
                        }
                    },
            )
    }

    private fun deadlocks(all: List<ScenarioResult>): Pair<String, Criterion> =
        count(DEADLOCKS, all, "0 deadlocks (SQLSTATE 40P01) across every scenario") { it.deadlocks }

    private fun lockTimeoutRate(chosen: List<ScenarioResult>): Pair<String, Criterion> {
        val worst = chosen.maxOfOrNull { it.lockTimeoutRate }
        return LOCK_TIMEOUTS to
            Criterion(
                LOCK_TIMEOUTS,
                pass = worst != null && worst < LOCK_TIMEOUT_RATE,
                observed = worst,
                threshold = "lock_timeout rate < $LOCK_TIMEOUT_RATE of events, every CHOSEN scenario",
                detail = "worst per-scenario rate over ${chosen.size} scenario(s)",
            )
    }

    private fun slotQueueing(all: List<ScenarioResult>): Pair<String, Criterion> =
        count(SLOT_QUEUEING, all, "0 events refused for W_slot (StorageUnavailableReason.SLOT_WAIT) across every scenario") {
            it.deadlineMissesFromSlotQueueing
        }

    private fun otherErrors(all: List<ScenarioResult>): Pair<String, Criterion> =
        count(OTHER_ERRORS, all, "0 errors other than lock_timeout, deadlock or slot wait, events and retention, across every scenario") {
            it.otherErrors
        }

    private fun starvedTicks(all: List<ScenarioResult>): Pair<String, Criterion> =
        count(STARVED_TICKS, all, "0 retention ticks that found nothing to delete, across every scenario") { it.retentionStarvedTicks }

    private fun count(
        name: String,
        all: List<ScenarioResult>,
        threshold: String,
        of: (ScenarioResult) -> Int,
    ): Pair<String, Criterion> {
        val total = all.sumOf(of)
        return name to
            Criterion(
                name,
                pass = all.isNotEmpty() && total == 0,
                observed = total.toDouble(),
                threshold = threshold,
                detail = "sum over ${all.size} scenario(s)",
            )
    }

    private fun coverageGaps(
        chosen: List<ScenarioResult>,
        references: Map<Triple<Int, Int, Int>, ScenarioResult>,
    ): List<String> {
        val gaps = mutableListOf<String>()
        val onRequiredPopulation = chosen.filter { it.workspaceCount >= requiredWorkspaces }
        if (onRequiredPopulation.isEmpty()) {
            gaps += "no CHOSEN scenario at ${2 * expectedRate} events/s on a population of >= $requiredWorkspaces workspaces"
        }
        val concurrencies = onRequiredPopulation.map { it.concurrency }.toSet()
        (requiredConcurrency - concurrencies).sorted().forEach {
            gaps += "concurrency $it missing at ${2 * expectedRate} events/s on >= $requiredWorkspaces workspaces"
        }
        chosen.forEach { c ->
            val reference = references[keyOf(c)]
            when {
                reference == null -> {
                    gaps += "no REFERENCE run for ${c.name}"
                }

                reference.referenceMode != ReferenceMode.NO_LOCK -> {
                    gaps += "reference for ${c.name} is ${reference.referenceMode}, not the no-ceiling mode c (NO_LOCK)"
                }
            }
            when {
                c.t1 == null -> gaps += "no T1 sample for ${c.name}"
                c.t1.samples < MIN_T1_SAMPLES -> gaps += "only ${c.t1.samples} T1 samples for ${c.name} (minimum $MIN_T1_SAMPLES)"
            }
            when {
                c.retention == null -> {
                    gaps += "no retention sample for ${c.name}"
                }

                c.retention.samples < MIN_RETENTION_SAMPLES -> {
                    gaps += "only ${c.retention.samples} retention samples for ${c.name} (minimum $MIN_RETENTION_SAMPLES)"
                }
            }
        }
        return gaps
    }

    private fun keyOf(s: ScenarioResult) = Triple(s.workspaceCount, s.concurrency, Math.round(s.offeredRate).toInt())

    private fun isRate(
        actual: Double,
        wanted: Double,
    ): Boolean = Math.abs(actual - wanted) <= wanted * RATE_TOLERANCE

    companion object {
        const val EXPECTED_RATE = 260.0
        val REQUIRED_CONCURRENCY = setOf(1, 10, 25, 50, 100)

        /** ADR-035 §11 as amended 2026-10-08: the concurrencies the offered-load criteria are read at. */
        val LOAD_GATED_CONCURRENCY = setOf(10, 25, 50, 100)
        const val REQUIRED_WORKSPACES = 10_000

        /**
         * "Sustained" needs a tolerance, because the achieved rate is
         * `completed / (last completion − first due)` and the last events'
         * own latency is inside that window. 0.995 allows a terminal lag of
         * 0.5 % of the run (150 ms at 30 s): three times the T1 p99 bound,
         * with the whole of T2 on top. A system that is merely finishing its
         * last events stays inside it; one that fell behind by even a few
         * hundred milliseconds does not. The naive derived form
         * `1 − p99(event) / duration` is NOT used: the terminal tail is one
         * event's latency, which exceeds p99 one time in a hundred, so that
         * form fails a healthy run whenever its last event is a slow one.
         */
        const val SUSTAINED_TOLERANCE = 0.995
        const val T1_P99_MS = 50.0
        const val RETENTION_FACTOR = 2.0
        const val LOCK_TIMEOUT_RATE = 0.001
        const val MIN_T1_SAMPLES = 1_000
        const val MIN_RETENTION_SAMPLES = 100
        private const val RATE_TOLERANCE = 0.01

        const val SUSTAINED = "sustained2x"
        const val T1_P99 = "t1P99"
        const val RETENTION = "retentionP99VsReference"
        const val DEADLOCKS = "deadlocks"
        const val LOCK_TIMEOUTS = "lockTimeoutRate"
        const val SLOT_QUEUEING = "slotQueueingDeadlineMisses"
        const val OTHER_ERRORS = "otherErrors"
        const val STARVED_TICKS = "retentionStarvedTicks"
    }
}
