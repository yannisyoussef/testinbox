package email.testinbox.benchmark

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The S-C1 timing probes: each class counted on its own; statistics only over the
 * events whose TARGET copy reached the guarded protocol; the unknown class never
 * reaches T1; repetitions pool per load point and mode with the sample size named.
 */
class ProbeLoadTest {
    private fun inbox() = SeededInbox(UUID.randomUUID(), UUID.randomUUID(), "x@bench.test")

    @Test
    fun `statistics cover only the events whose target copy reached the protocol`() {
        val hot = listOf(inbox(), inbox())
        val observer = inbox()
        val limited = hot.first() // its INGEST budget is gone: the event reaches T1 with the observer's copy alone
        val load =
            ProbeLoad(
                mapOf(
                    "inside" to hot.map { listOf(it, observer) },
                    "cold" to listOf(listOf(inbox())),
                    "unknown" to listOf(emptyList()),
                ),
                ratePerSecond = 200.0,
                durationSeconds = 1,
                seed = 1,
            ) { inboxes -> ProbeResult(ProbeOutcome.DELIVERED, targetReached = inboxes.first() != limited) }
        load.start()
        load.join()
        val s = load.summaries()
        s.getValue("inside").events shouldBe 200
        s.getValue("inside").targetReachedEvents shouldBe 100 // rotation: every other event's hot copy was rate-limited
        s.getValue("inside").latency?.samples shouldBe 100
        s.getValue("cold").targetReachedEvents shouldBe 200
        s.getValue("unknown").reachesT1 shouldBe false
        s.getValue("unknown").targetReachedEvents shouldBe 0
    }

    @Test
    fun `the 451 rate is over target-reached events`() {
        val target = inbox()
        val load =
            ProbeLoad(mapOf("cold" to listOf(listOf(target))), 100.0, 1, 2) { ProbeResult(ProbeOutcome.UNAVAILABLE, true) }
        load.start()
        load.join()
        load.summaries().getValue("cold").unavailableRate shouldBeExactly 1.0
    }

    @Test
    fun `repetitions pool per load point and mode, naming the sample behind each p99`() {
        fun run(
            name: String,
            mode: AdmissionMode,
            reached: Int,
        ): ScenarioResult {
            val summary = ProbeSummary(reached, reached, null, 0.0, true, List(reached) { 1_000_000L * (it + 1) }, 0)
            return ProbeVariantFixture.result(name, mode, mapOf("inside" to summary, "cold" to summary))
        }
        val pooled =
            ProbePooling.pool(
                listOf(
                    run("chosen-ws31-c10-r520-probe-rep1", AdmissionMode.CHOSEN, 160),
                    run("chosen-ws31-c10-r520-probe-rep2", AdmissionMode.CHOSEN, 160),
                    run("chosen-ws31-c10-r5-probe-paced-rep1", AdmissionMode.CHOSEN, 90),
                ),
            )
        pooled.size shouldBe 2
        val heaviest = pooled.single { it["point"] == "heaviest" }
        heaviest["repetitions"] shouldBe 2
        @Suppress("UNCHECKED_CAST")
        val inside = (heaviest["classes"] as Map<String, Map<String, Any?>>).getValue("inside")
        inside["targetReachedEvents"] shouldBe 320
        inside["minimumSampleMet"] shouldBe true
        @Suppress("UNCHECKED_CAST")
        val paced = (pooled.single { it["point"] == "paced" }["classes"] as Map<String, Map<String, Any?>>).getValue("inside")
        paced["minimumSampleMet"] shouldBe false
        heaviest["insideMinusColdP99Ms"] shouldBe 0.0
    }

    @Test
    fun `probe, paced and repeated scenarios are named apart, so the informational criteria can leave them out`() {
        ScenarioSpec(AdmissionMode.CHOSEN, 10, 520.0, probe = true).name(31) shouldBe "chosen-ws31-c10-r520-probe"
        ScenarioSpec(AdmissionMode.CHOSEN, 10, 1.0, probe = true, paced = true, repetition = 2).name(31) shouldBe
            "chosen-ws31-c10-r1-probe-paced-rep2"
        ScenarioSpec(AdmissionMode.PARTITIONED, 10, 520.0, hot = true).name(31) shouldBe "partitioned-ws31-c10-r520-hot"
    }
}

/** A minimal scenario result carrying probe summaries, for the pooling test. */
internal object ProbeVariantFixture {
    fun result(
        name: String,
        mode: AdmissionMode,
        probes: Map<String, ProbeSummary>,
    ) = ScenarioResult(
        name = name,
        mode = mode,
        referenceMode = null,
        workspaceCount = 31,
        inboxCount = 310,
        reservationBacklog = 0,
        deltaBacklog = 0,
        deltaBacklogObserved = null,
        concurrency = 10,
        offeredRate = 1.0,
        achievedRate = 1.0,
        durationSeconds = 30.0,
        scheduledEvents = 1,
        completedEvents = 1,
        terminalLagMs = 0.0,
        copiesCommitted = 0,
        t1 = null,
        t1Transaction = null,
        t2 = null,
        event = null,
        retention = null,
        retentionOfferedRate = 0.0,
        retentionAchievedRate = 0.0,
        retentionStarvedTicks = 0,
        lockWait = null,
        slotWait = null,
        lockTimeouts = 0,
        lockTimeoutRate = 0.0,
        deadlocks = 0,
        deadlineMissesFromSlotQueueing = 0,
        otherErrors = 0,
        errorSamples = emptyList(),
        physicalFailures = emptyMap(),
        probes = probes,
    )
}
