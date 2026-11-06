package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * `--escrow-variant` (slot-lane escrow M1 prototype, ADR-038 PROPOSED): NON-GATE,
 * never ADR evidence, on the closed cohort only, V28-proto shipped only as a benchmark
 * resource, and 124/s labelled a stress diagnostic (owner, 2026-11-06).
 */
class EscrowVariantTest {
    @Test
    fun `the variant needs the closed cohort and is never ADR evidence`() {
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--escrow-variant")) }.message shouldContain
            "--focused-cohort"
        val options = BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--escrow-variant", "--escrow-lane-bump-seconds", "5"))
        options.escrowVariant shouldBe true
        options.escrowLaneBumpSeconds shouldBe 5
        AdrConformance.reasons(options) shouldContain "--escrow-variant: the slot-lane escrow M1 prototype (ADR-038 PROPOSED), NON-GATE"
        shouldThrow<UsageException> {
            BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--escrow-variant", "--escrow-lane-bump-seconds", "-1"))
        }
    }

    @Test
    fun `62 per second is the only gate load the report reads, and 124 is a stress diagnostic`() {
        EscrowScenarioReport.label(62.0) shouldStartWith "NON-GATE"
        EscrowScenarioReport.label(31.0) shouldStartWith "NON-GATE"
        EscrowScenarioReport.label(124.0) shouldStartWith "STRESS DIAGNOSTIC: not a criterion"
    }

    @Test
    fun `V28-proto ships only as a benchmark resource, never on a Flyway location`() {
        checkNotNull(javaClass.getResource(EscrowProto.RESOURCE)) { "the benchmark carries V28-proto" }
        val migrations = javaClass.classLoader.getResources("db/migration").toList()
        migrations.isNotEmpty() shouldBe true
        EscrowProto.RESOURCE shouldStartWith "/escrow-proto/"
        javaClass.classLoader.getResource("db/migration/v28-proto-slot-lane-escrow.sql") shouldBe null
    }
}
