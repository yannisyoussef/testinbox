package email.testinbox.benchmark

import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** The S-C1 timing probes: each class counted on its own; the unknown class never reaches T1. */
class ProbeLoadTest {
    private fun inbox() = SeededInbox(UUID.randomUUID(), UUID.randomUUID(), "x@bench.test")

    @Test
    fun `each class is summarized on its own, and an empty class never reaches T1`() {
        val hot = inbox()
        val load =
            ProbeLoad(
                mapOf("inside" to listOf(hot, inbox()), "cold" to listOf(inbox()), "unknown" to emptyList()),
                ratePerSecond = 200.0,
                durationSeconds = 1,
                seed = 1,
            ) { inboxes -> if (hot in inboxes) ProbeOutcome.UNAVAILABLE else ProbeOutcome.DELIVERED }
        load.start()
        load.join()
        val s = load.summaries()
        s.getValue("inside").unavailableRate shouldBeExactly 1.0
        s.getValue("cold").unavailableRate shouldBeExactly 0.0
        s.getValue("unknown").reachesT1 shouldBe false
        s.getValue("cold").reachesT1 shouldBe true
        s.values.forEach { it.events shouldBe 200 }
    }

    @Test
    fun `probe and hot scenarios are named apart, so the informational criteria can leave them out`() {
        ScenarioSpec(AdmissionMode.CHOSEN, 10, 520.0, probe = true).name(31) shouldBe "chosen-ws31-c10-r520-probe"
        ScenarioSpec(AdmissionMode.PARTITIONED, 10, 520.0, hot = true).name(31) shouldBe "partitioned-ws31-c10-r520-hot"
    }
}
