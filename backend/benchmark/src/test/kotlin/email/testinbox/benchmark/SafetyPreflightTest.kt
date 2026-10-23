package email.testinbox.benchmark

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class SafetyPreflightTest {
    private val empty = mapOf("workspace" to 0L, "inbox" to 0L, "message" to 0L)

    private fun facts(
        ack: String? = SafetyPreflight.ACK_VALUE,
        name: String = "testinbox_bench",
        counts: Map<String, Long> = empty,
        created: Boolean = false,
    ) = PreflightFacts(ack, name, counts, created)

    private fun refused(facts: PreflightFacts): List<String> =
        SafetyPreflight.decide(facts).shouldBeInstanceOf<PreflightDecision.Refused>().reasons

    @Test
    fun `an acknowledged, bench-named, empty database is allowed and the proof names the counts`() {
        val allowed = SafetyPreflight.decide(facts()).shouldBeInstanceOf<PreflightDecision.Allowed>()
        allowed.proof shouldContain "testinbox_bench"
        allowed.proof shouldContain "workspace=0"
        allowed.proof shouldContain "pre-existing"
    }

    @Test
    fun `a database this run created is allowed while it is empty`() {
        SafetyPreflight.decide(facts(created = true)).shouldBeInstanceOf<PreflightDecision.Allowed>().proof shouldContain
            "created by this run"
    }

    @Test
    fun `no acknowledgement refuses, whatever else holds`() {
        refused(facts(ack = null)).single() shouldContain SafetyPreflight.ACK_VARIABLE
        refused(facts(ack = "yes")).single() shouldContain SafetyPreflight.ACK_VARIABLE
        refused(facts(ack = SafetyPreflight.ACK_VALUE.lowercase())).single() shouldContain SafetyPreflight.ACK_VARIABLE
        refused(facts(ack = null, created = true)).single() shouldContain SafetyPreflight.ACK_VARIABLE
    }

    @Test
    fun `a database whose name lacks bench refuses`() {
        refused(facts(name = "testinbox")).single() shouldContain "does not contain 'bench'"
        refused(facts(name = "testinbox_staging")).single() shouldContain "does not contain 'bench'"
        SafetyPreflight.decide(facts(name = "BENCH_ti")).shouldBeInstanceOf<PreflightDecision.Allowed>()
    }

    @Test
    fun `tenant rows in a pre-existing database refuse, and the acknowledgement does not override it`() {
        val reasons = refused(facts(counts = mapOf("workspace" to 3L, "inbox" to 0L, "message" to 0L)))
        reasons.single() shouldContain "already holds tenant rows (workspace=3)"
        refused(facts(counts = mapOf("workspace" to 0L, "inbox" to 0L, "message" to 1L))).single() shouldContain "message=1"
    }

    @Test
    fun `tenant rows in a database this run supposedly created refuse too`() {
        refused(facts(counts = mapOf("workspace" to 1L, "inbox" to 0L, "message" to 0L), created = true)).single() shouldContain
            "created by this run but already holds tenant rows"
    }

    @Test
    fun `row counts must have been established for every tenant table`() {
        refused(facts(counts = mapOf("workspace" to 0L))).single() shouldContain "inbox, message"
    }

    @Test
    fun `every failing condition is reported, not just the first`() {
        val reasons = refused(facts(ack = null, name = "prod", counts = mapOf("workspace" to 9L, "inbox" to 9L, "message" to 9L)))
        reasons.size shouldBe 3
    }

    @Test
    fun `the acknowledgement value is the documented sentence`() {
        SafetyPreflight.ACK_VARIABLE shouldBe "TESTINBOX_BENCHMARK_DESTRUCTIVE_ACK"
        SafetyPreflight.ACK_VALUE shouldBe "I_UNDERSTAND_THIS_DESTROYS_THE_TARGET_DATABASE"
    }

    @Test
    fun `the database name is read from the JDBC URL`() {
        BenchmarkTarget.databaseNameOf("jdbc:postgresql://db.internal:5432/testinbox_bench?sslmode=require") shouldBe "testinbox_bench"
        BenchmarkTarget.databaseNameOf("jdbc:postgresql://localhost/bench") shouldBe "bench"
    }
}
