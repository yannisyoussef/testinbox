package email.testinbox.benchmark

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * ADR-035 §11 encoded exactly. The negatives are mutation-style: a matrix
 * that passes, with ONE criterion broken in ONE scenario, must FAIL on that
 * criterion alone. A threshold that could be loosened without a test here
 * noticing is not a gate.
 */
class GateEvaluatorTest {
    private val evaluator = GateEvaluator()

    private fun latency(
        p99: Double,
        samples: Int = 15_000,
    ) = LatencySummary(samples, p99 / 2, p99 * 0.9, p99, p99 * 1.2)

    @Suppress("LongParameterList")
    private fun scenario(
        mode: AdmissionMode,
        concurrency: Int,
        rate: Double,
        workspaces: Int = 10_000,
        achieved: Double = rate,
        t1P99: Double = 10.0,
        retentionP99: Double = 5.0,
        lockTimeouts: Int = 0,
        deadlocks: Int = 0,
        slotMisses: Int = 0,
        otherErrors: Int = 0,
        starved: Int = 0,
        scheduled: Int = 15_600,
        referenceMode: ReferenceMode? = if (mode == AdmissionMode.REFERENCE) ReferenceMode.NO_LOCK else null,
    ) = ScenarioResult(
        name = "${mode.name.lowercase()}-ws$workspaces-c$concurrency-r${rate.toInt()}",
        mode = mode,
        referenceMode = referenceMode,
        workspaceCount = workspaces,
        inboxCount = workspaces * 10,
        reservationBacklog = 1_000,
        deltaBacklog = 500,
        deltaBacklogObserved = DeltaBacklogObserved(120, 2_400.0, 9_800),
        concurrency = concurrency,
        offeredRate = rate,
        achievedRate = achieved,
        durationSeconds = 30.0,
        scheduledEvents = scheduled,
        completedEvents = scheduled - lockTimeouts - deadlocks - slotMisses - otherErrors,
        terminalLagMs = 12.0,
        copiesCommitted = scheduled * 4L,
        t1 = latency(t1P99),
        t1Transaction = latency(t1P99 / 2),
        t2 = latency(8.0),
        event = latency(t1P99 + 8.0),
        retention = latency(retentionP99, samples = 780),
        retentionOfferedRate = rate / 20,
        retentionAchievedRate = rate / 20,
        retentionStarvedTicks = starved,
        lockWait = latency(t1P99 / 2),
        slotWait = latency(0.1),
        lockTimeouts = lockTimeouts,
        lockTimeoutRate = lockTimeouts.toDouble() / scheduled,
        deadlocks = deadlocks,
        deadlineMissesFromSlotQueueing = slotMisses,
        otherErrors = otherErrors,
        errorSamples = emptyList(),
        physicalFailures = emptyMap(),
    )

    /** The full §11 matrix: both rates, every concurrency, chosen and NO_LOCK reference, on 10 000 workspaces. */
    private fun passingMatrix(): List<ScenarioResult> =
        listOf(260.0, 520.0).flatMap { rate ->
            GateEvaluator.REQUIRED_CONCURRENCY.flatMap { c ->
                listOf(scenario(AdmissionMode.CHOSEN, c, rate), scenario(AdmissionMode.REFERENCE, c, rate))
            }
        }

    private fun List<ScenarioResult>.mutate(
        concurrency: Int,
        rate: Double = 520.0,
        mode: AdmissionMode = AdmissionMode.CHOSEN,
        change: (ScenarioResult) -> ScenarioResult,
    ): List<ScenarioResult> = map { if (it.mode == mode && it.concurrency == concurrency && it.offeredRate == rate) change(it) else it }

    private fun GateVerdict.failsOnly(criterion: String) {
        verdict shouldBe Verdict.FAIL
        incompleteReasons.shouldBeEmpty()
        criteria.getValue(criterion).pass shouldBe false
        criteria.filterKeys { it != criterion }.values.all { it.pass } shouldBe true
    }

    @Test
    fun `the full matrix within every threshold passes`() {
        val verdict = evaluator.evaluate(passingMatrix())
        verdict.verdict shouldBe Verdict.PASS
        verdict.incompleteReasons.shouldBeEmpty()
        verdict.criteria.keys shouldBe
            setOf(
                GateEvaluator.SUSTAINED,
                GateEvaluator.T1_P99,
                GateEvaluator.RETENTION,
                GateEvaluator.DEADLOCKS,
                GateEvaluator.LOCK_TIMEOUTS,
                GateEvaluator.SLOT_QUEUEING,
                GateEvaluator.OTHER_ERRORS,
                GateEvaluator.STARVED_TICKS,
            )
        verdict.criteria.values.all { it.pass } shouldBe true
        verdict.criteria.getValue(GateEvaluator.RETENTION).detail shouldContain "reference mode NO_LOCK"
    }

    @Test
    fun `boundary values pass exactly at the thresholds`() {
        val matrix =
            passingMatrix()
                .mutate(25) { it.copy(t1 = latency(50.0)) }
                .mutate(50) { it.copy(retention = latency(10.0, 780)) } // reference is 5.0: exactly 2×
                .mutate(100) { it.copy(achievedRate = 520.0 * 0.995) }
                .mutate(1) { it.copy(t1 = latency(10.0, samples = 1_000), retention = latency(5.0, samples = 100)) }
        evaluator.evaluate(matrix).verdict shouldBe Verdict.PASS
    }

    @Test
    fun `2x load not sustained fails only the sustained criterion`() {
        val matrix = passingMatrix().mutate(100) { it.copy(achievedRate = 520.0 * 0.994) }
        val verdict = evaluator.evaluate(matrix)
        verdict.failsOnly(GateEvaluator.SUSTAINED)
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).observed!! shouldBe 0.994
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).detail shouldContain "terminal lag"
    }

    @Test
    fun `T1 p99 above 50 ms at 2x fails only the T1 criterion`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(50) { it.copy(t1 = latency(50.01)) })
        verdict.failsOnly(GateEvaluator.T1_P99)
        verdict.criteria.getValue(GateEvaluator.T1_P99).observed shouldBe 50.01
    }

    @Test
    fun `concurrency 1 failing the offered-load criteria at 2x does not fail the gate - the uncontended baseline (ADR §11 as amended)`() {
        val matrix = passingMatrix().mutate(1) { it.copy(achievedRate = 301.5, t1 = latency(412.7), terminalLagMs = 12_000.0) }
        val verdict = evaluator.evaluate(matrix)
        verdict.verdict shouldBe Verdict.PASS
        verdict.incompleteReasons.shouldBeEmpty()
        // Reported, not judged: the worst sustained/T1 figures come from the load-gated concurrencies.
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).observed!! shouldBe 1.0
        verdict.criteria.getValue(GateEvaluator.T1_P99).observed shouldBe 10.0
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).threshold shouldContain "concurrency 1 is the uncontended baseline"
    }

    @Test
    fun `concurrency 1 is still required for coverage, and its integrity failures still fail the gate`() {
        val without = passingMatrix().filterNot { it.concurrency == 1 && it.offeredRate == 520.0 && it.mode == AdmissionMode.CHOSEN }
        evaluator.evaluate(without).verdict shouldBe Verdict.INCOMPLETE
        evaluator.evaluate(passingMatrix().mutate(1) { it.copy(deadlocks = 1) }).failsOnly(GateEvaluator.DEADLOCKS)
        evaluator.evaluate(passingMatrix().mutate(1) { it.copy(otherErrors = 2) }).failsOnly(GateEvaluator.OTHER_ERRORS)
        // The retention ratio is relative to a reference at the same offered load, so it is judged at concurrency 1 too.
        evaluator.evaluate(passingMatrix().mutate(1) { it.copy(retention = latency(10.01, 780)) }).failsOnly(GateEvaluator.RETENTION)
    }

    @Test
    fun `the same offered-load shortfall at a gated concurrency still fails`() {
        evaluator.evaluate(passingMatrix().mutate(10) { it.copy(achievedRate = 301.5) }).failsOnly(GateEvaluator.SUSTAINED)
        evaluator.evaluate(passingMatrix().mutate(25) { it.copy(t1 = latency(412.7)) }).failsOnly(GateEvaluator.T1_P99)
    }

    @Test
    fun `T1 p99 above 50 ms at 1x does not fail the gate, since the criterion is read at 2x`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(50, rate = 260.0) { it.copy(t1 = latency(400.0)) })
        verdict.verdict shouldBe Verdict.PASS
    }

    @Test
    fun `retention p99 above twice the reference fails only the retention criterion`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(10) { it.copy(retention = latency(10.01, 780)) })
        verdict.failsOnly(GateEvaluator.RETENTION)
        verdict.criteria.getValue(GateEvaluator.RETENTION).observed!! shouldBe (10.01 / 5.0)
    }

    @Test
    fun `a faster reference at the same load is what makes retention fail`() {
        val verdict =
            evaluator.evaluate(
                passingMatrix().mutate(10, mode = AdmissionMode.REFERENCE) { it.copy(retention = latency(2.0, 780)) },
            )
        verdict.failsOnly(GateEvaluator.RETENTION)
    }

    @Test
    fun `a CEILING_OFF reference is a self-comparison, so it is INCOMPLETE and cannot pass retention`() {
        val matrix = passingMatrix().mutate(10, mode = AdmissionMode.REFERENCE) { it.copy(referenceMode = ReferenceMode.CEILING_OFF) }
        val verdict = evaluator.evaluate(matrix)
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons.single() shouldContain
            "reference for chosen-ws10000-c10-r520 is CEILING_OFF, not the no-ceiling mode c (NO_LOCK)"
        val retention = verdict.criteria.getValue(GateEvaluator.RETENTION)
        retention.pass shouldBe false
        retention.detail shouldContain "CEILING_OFF"
        retention.detail shouldContain "same lock"
    }

    @Test
    fun `one deadlock anywhere, even at 1x, fails only the deadlock criterion`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(1, rate = 260.0) { it.copy(deadlocks = 1) })
        verdict.failsOnly(GateEvaluator.DEADLOCKS)
        verdict.criteria.getValue(GateEvaluator.DEADLOCKS).observed shouldBe 1.0
    }

    @Test
    fun `a lock_timeout rate at or above 0,1 percent fails only the lock timeout criterion`() {
        // 16 of 15 600 = 0.1026 %.
        val verdict = evaluator.evaluate(passingMatrix().mutate(100) { it.copy(lockTimeouts = 16, lockTimeoutRate = 16.0 / 15_600) })
        verdict.failsOnly(GateEvaluator.LOCK_TIMEOUTS)
        // Exactly 0.1 % is not "< 0.1 %".
        evaluator.evaluate(passingMatrix().mutate(100) { it.copy(lockTimeoutRate = 0.001) }).failsOnly(GateEvaluator.LOCK_TIMEOUTS)
        // 15 of 15 600 = 0.096 % passes.
        evaluator.evaluate(passingMatrix().mutate(100) { it.copy(lockTimeoutRate = 15.0 / 15_600) }).verdict shouldBe Verdict.PASS
    }

    @Test
    fun `a single slot-queueing deadline miss fails only that criterion`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(25, rate = 260.0) { it.copy(deadlineMissesFromSlotQueueing = 1) })
        verdict.failsOnly(GateEvaluator.SLOT_QUEUEING)
    }

    @Test
    fun `a single other error, even in a reference run, fails only the other-errors criterion`() {
        val verdict =
            evaluator.evaluate(passingMatrix().mutate(25, rate = 260.0, mode = AdmissionMode.REFERENCE) { it.copy(otherErrors = 1) })
        verdict.failsOnly(GateEvaluator.OTHER_ERRORS)
        verdict.criteria.getValue(GateEvaluator.OTHER_ERRORS).observed shouldBe 1.0
    }

    @Test
    fun `a single starved retention tick fails only the starved-ticks criterion`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(50) { it.copy(retentionStarvedTicks = 1) })
        verdict.failsOnly(GateEvaluator.STARVED_TICKS)
    }

    @Test
    fun `two broken criteria both show as failed`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(50) { it.copy(t1 = latency(80.0), deadlocks = 2) })
        verdict.verdict shouldBe Verdict.FAIL
        verdict.criteria.getValue(GateEvaluator.T1_P99).pass shouldBe false
        verdict.criteria.getValue(GateEvaluator.DEADLOCKS).pass shouldBe false
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).pass shouldBe true
    }

    @Test
    fun `a missing concurrency level is INCOMPLETE, never PASS`() {
        val matrix = passingMatrix().filterNot { it.concurrency == 100 && it.offeredRate == 520.0 }
        val verdict = evaluator.evaluate(matrix)
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons.single() shouldContain "concurrency 100 missing"
    }

    @Test
    fun `a matrix on fewer than 10 000 workspaces is INCOMPLETE`() {
        val matrix = passingMatrix().map { it.copy(workspaceCount = 200) }
        val verdict = evaluator.evaluate(matrix)
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons.first() shouldContain ">= 10000 workspaces"
    }

    @Test
    fun `a missing reference run is INCOMPLETE and the retention criterion cannot pass`() {
        val matrix = passingMatrix().filterNot { it.mode == AdmissionMode.REFERENCE && it.concurrency == 10 && it.offeredRate == 520.0 }
        val verdict = evaluator.evaluate(matrix)
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons shouldContain "no REFERENCE run for chosen-ws10000-c10-r520"
        verdict.criteria.getValue(GateEvaluator.RETENTION).pass shouldBe false
    }

    @Test
    fun `a scenario with no T1 or retention sample is INCOMPLETE`() {
        val verdict = evaluator.evaluate(passingMatrix().mutate(1) { it.copy(t1 = null, retention = null) })
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons shouldContain "no T1 sample for chosen-ws10000-c1-r520"
        verdict.incompleteReasons shouldContain "no retention sample for chosen-ws10000-c1-r520"
    }

    @Test
    fun `too few samples in a 2x scenario is INCOMPLETE, below 1000 T1 or 100 retention`() {
        val t1 = evaluator.evaluate(passingMatrix().mutate(10) { it.copy(t1 = latency(10.0, samples = 999)) })
        t1.verdict shouldBe Verdict.INCOMPLETE
        t1.incompleteReasons.single() shouldContain "only 999 T1 samples for chosen-ws10000-c10-r520 (minimum 1000)"
        val retention = evaluator.evaluate(passingMatrix().mutate(10) { it.copy(retention = latency(5.0, samples = 99)) })
        retention.verdict shouldBe Verdict.INCOMPLETE
        retention.incompleteReasons.single() shouldContain "only 99 retention samples for chosen-ws10000-c10-r520 (minimum 100)"
        // The floors apply at 2x; a 1x scenario with few samples is reported, not a gap.
        evaluator.evaluate(passingMatrix().mutate(10, rate = 260.0) { it.copy(retention = latency(5.0, samples = 3)) }).verdict shouldBe
            Verdict.PASS
    }

    @Test
    fun `inputs that departed from section 11 force INCOMPLETE even on a passing matrix`() {
        val verdict = evaluator.evaluate(passingMatrix(), nonAdrReasons = listOf("--local runs against a throwaway container"))
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons.single() shouldBe "not ADR evidence: --local runs against a throwaway container"
        verdict.criteria.values.all { it.pass } shouldBe true
    }

    @Test
    fun `an incomplete matrix still reports which criteria it would have failed`() {
        val smoke = listOf(scenario(AdmissionMode.CHOSEN, 1, 50.0, workspaces = 50, deadlocks = 1))
        val verdict = evaluator.evaluate(smoke)
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.incompleteReasons.shouldNotBeEmpty()
        verdict.criteria.getValue(GateEvaluator.DEADLOCKS).pass shouldBe false
        verdict.criteria.getValue(GateEvaluator.SUSTAINED).detail shouldContain "no CHOSEN scenario at 2x"
    }

    @Test
    fun `nothing at all is INCOMPLETE with every criterion unmet`() {
        val verdict = evaluator.evaluate(emptyList())
        verdict.verdict shouldBe Verdict.INCOMPLETE
        verdict.criteria.values.none { it.pass } shouldBe true
    }

    @Test
    fun `the expected rate is configurable and the 2x point follows it`() {
        val evaluator = GateEvaluator(expectedRate = 100.0)
        val matrix =
            listOf(100.0, 200.0).flatMap { rate ->
                GateEvaluator.REQUIRED_CONCURRENCY.flatMap { c ->
                    listOf(scenario(AdmissionMode.CHOSEN, c, rate), scenario(AdmissionMode.REFERENCE, c, rate))
                }
            }
        evaluator.evaluate(matrix).verdict shouldBe Verdict.PASS
        evaluator.evaluate(matrix.mutate(10, rate = 200.0) { it.copy(t1 = latency(51.0)) }).verdict shouldBe Verdict.FAIL
    }

    @Test
    fun `the thresholds are the ADR's, verbatim`() {
        GateEvaluator.T1_P99_MS shouldBe 50.0
        GateEvaluator.RETENTION_FACTOR shouldBe 2.0
        GateEvaluator.LOCK_TIMEOUT_RATE shouldBe 0.001
        GateEvaluator.EXPECTED_RATE shouldBe 260.0
        GateEvaluator.REQUIRED_CONCURRENCY shouldBe setOf(1, 10, 25, 50, 100)
        GateEvaluator.LOAD_GATED_CONCURRENCY shouldBe setOf(10, 25, 50, 100)
        GateEvaluator.REQUIRED_WORKSPACES shouldBe 10_000
        GateEvaluator.SUSTAINED_TOLERANCE shouldBe 0.995
        GateEvaluator.MIN_T1_SAMPLES shouldBe 1_000
        GateEvaluator.MIN_RETENTION_SAMPLES shouldBe 100
        GateVerdict.ADR_REVIEW_REQUIRED shouldBe "ADR REVIEW REQUIRED (per-node escrow is the named fallback, not implemented)"
    }
}
