package email.testinbox.benchmark

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** T1's in-lock planning: cold-connection plans counted client side, invalidation re-plans derived from the server's total. */
class T1PlanningTest {
    private val ms = 1_000_000L

    /** A completed T1 due at 0 that ended at [t1Ms], whose snapshot was execution [ordinal] on its connection and took [snapshotMs]. */
    private fun event(
        ordinal: Int,
        t1Ms: Long,
        snapshotMs: Long,
    ) = TimedEvent(
        0L,
        EventTimings().apply {
            t1EndNanos = t1Ms * ms
            steps.statement(HoldTiming.Step.LOCK, 1, 2)
            steps.statement(HoldTiming.Step.SNAPSHOT, 3, 3 + snapshotMs * ms, ordinal)
        },
    )

    private val events =
        listOf(event(1, 30, 14), event(5, 28, 13)) + List(8) { event(PoolLifecycle.PREPARE_THRESHOLD + 1 + it, 6, 1) }

    private val phase = LedgerPhase(snapshotExecutions = 12, coldExecutions = 2, physicalConnections = 2, firstUsedInPhase = 1)

    @Test
    fun `a T1 is cold when its snapshot is among its connection's first prepareThreshold executions`() {
        val planning = T1Planning.of(phase, events, stats(plans = 5, totalPlanMs = 60.0))!!
        val client = planning.client.shouldNotBeNull()
        client.coldT1!!.samples shouldBe 2
        client.warmT1!!.samples shouldBe 8
        client.coldT1.p99Ms shouldBe 30.0
        client.warmT1.p99Ms shouldBe 6.0
        client.coldSnapshotMeanMs!! shouldBe (13.5 plusOrMinus 1e-6)
        client.warmSnapshotMeanMs!! shouldBe (1.0 plusOrMinus 1e-6)
        planning.coldPlanCostMs!! shouldBe (12.5 plusOrMinus 1e-6)
        // 5 plans on the server, 2 of them cold-connection plans: 3 re-plans an invalidation forced.
        planning.invalidationReplans shouldBe 3L
        planning.server!!.totalPlanMs shouldBe 60.0
    }

    @Test
    fun `without step timing only the server's figures remain, and nothing is derived`() {
        val planning = T1Planning.of(null, events, stats(plans = 5, totalPlanMs = 60.0))!!
        planning.client.shouldBeNull()
        planning.invalidationReplans.shouldBeNull()
        planning.coldPlanCostMs.shouldBeNull()
        T1Planning.of(null, events, null).shouldBeNull()
        // track_planning off: 0 plans is no information, never "no re-plans".
        T1Planning.of(phase, events, stats(plans = 0, totalPlanMs = 0.0))!!.invalidationReplans.shouldBeNull()
    }

    @Test
    fun `FOCUSED md states the lifecycle and splits plans and T1 p99 by connection kind`() {
        val planning = T1Planning.of(phase, events, stats(plans = 5, totalPlanMs = 60.0))
        val result =
            EvidenceTest.sample().copy(
                pool = PoolPhase(true, 60_000, 0, 16, connectionsOpenedBeforePhase = 32, connectionsOpenedInPhase = 16),
                t1Planning = planning,
            )
        val cold =
            FocusedReport.render(
                listOf(result),
                BenchmarkOptions.parse(listOf("--local", "--focused", "--pool-max-lifetime", "60s")),
            )
        cold shouldContain "coldPool true; poolMaxLifetime PT1M (production PT30M)"
        cold shouldContain "| pool size / connections opened before phase / in phase [measured, Hikari] | 16 / 32 / 16 |"
        cold shouldContain "| snapshot executions / cold-connection plans in lock [measured, client] | 12 / 2 |"
        cold shouldContain "| invalidation re-plans in lock [derived: server − client cold] | 3 |"
        cold shouldContain "| **T1 p99, cold / warm connection** (completion − due) | 30.00 / 6.00 |"
        cold shouldContain "COLD pool"
        FocusedReport.render(listOf(result), BenchmarkOptions.parse(listOf("--local", "--focused", "--warm-pool"))) shouldContain
            "WARM pool (--warm-pool)"
    }

    private fun stats(
        plans: Long,
        totalPlanMs: Double,
    ) = StatementStats(
        calls = 12,
        plans = plans,
        meanPlanMs = if (plans == 0L) 0.0 else totalPlanMs / plans,
        meanExecMs = 1.0,
        maxExecMs = 2.0,
        rowsPerCall = 40.0,
        totalPlanMs = totalPlanMs,
    )
}
