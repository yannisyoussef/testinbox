package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class BenchmarkOptionsTest {
    @Test
    fun `defaults are the ADR-035 section 11 matrix`() {
        val options = BenchmarkOptions.parse(listOf("--local"))
        options.workspaces shouldBe listOf(10_000)
        options.concurrency shouldBe listOf(1, 10, 25, 50, 100)
        options.rates shouldBe listOf(260.0, 520.0)
        options.reservationBacklog shouldBe 1_000
        options.deltaBacklog shouldBe 500
        options.reference shouldBe true
        options.referenceMode shouldBe ReferenceMode.NO_LOCK
        options.hostClass shouldBe AdrConformance.UNSPECIFIED_HOST_CLASS
        options.retentionRateFor(520.0) shouldBe 26.0
    }

    @Test
    fun `flags are parsed`() {
        val options =
            BenchmarkOptions.parse(
                listOf(
                    "--jdbc-url",
                    "jdbc:postgresql://db/ti_bench",
                    "--create-database",
                    "--workspaces",
                    "200,10000",
                    "--concurrency",
                    "1,10",
                    "--rates",
                    "50",
                    "--expected-rate",
                    "25",
                    "--duration-seconds",
                    "5",
                    "--mix",
                    "1:1,50:1",
                    "--host-class",
                    "hetzner-cx32",
                    "--reference-mode",
                    "ceiling-off",
                    "--arrivals",
                    "uniform",
                ),
            )
        options.jdbcUrl shouldBe "jdbc:postgresql://db/ti_bench"
        options.createDatabase shouldBe true
        options.workspaces shouldBe listOf(200, 10_000)
        options.concurrency shouldBe listOf(1, 10)
        options.rates shouldBe listOf(50.0)
        options.expectedRate shouldBe 25.0
        options.recipientMix.toString() shouldBe "1:1,50:1"
        options.hostClass shouldBe "hetzner-cx32"
        options.referenceMode shouldBe ReferenceMode.CEILING_OFF
        options.arrivals shouldBe Arrivals.UNIFORM
    }

    @Test
    fun `the measured window is 30 s at R = 260 and 33 s at R = 31, enough for 100 retention samples at 2R`() {
        BenchmarkOptions.parse(listOf("--local")).measuredSeconds shouldBe 30
        val beta = BenchmarkOptions.parse(listOf("--local", "--expected-rate", "31", "--rates", "31,62"))
        beta.measuredSeconds shouldBe 33
        // The scenario runner's retention events at 2R: ceil(3.1 × 33) = 103 ≥ 100; 32 s would give exactly 100.
        Math.ceil(beta.retentionRateFor(62.0) * beta.measuredSeconds).toInt() shouldBe 103
        (Math.ceil(beta.retentionRateFor(62.0) * 30).toInt() < GateEvaluator.MIN_RETENTION_SAMPLES) shouldBe true
        // An explicit window wins; a forced retention rate changes the derived one.
        BenchmarkOptions.parse(listOf("--local", "--expected-rate", "31", "--duration-seconds", "45")).measuredSeconds shouldBe 45
        BenchmarkOptions.parse(listOf("--local", "--expected-rate", "31", "--retention-rate", "10")).measuredSeconds shouldBe 30
    }

    @Test
    fun `the gate population carries the cohort shares unless told otherwise`() {
        BenchmarkOptions.parse(listOf("--local")).cohortShares shouldBe true
        BenchmarkOptions.parse(listOf("--local", "--no-cohort-shares")).cohortShares shouldBe false
    }

    @Test
    fun `--focused is CHOSEN only at 62 per second, concurrency 10 and 100, 10 000 workspaces with the shares, 60 s`() {
        val focused = BenchmarkOptions.parse(listOf("--local", "--focused"))
        focused.focused shouldBe true
        focused.reference shouldBe false
        focused.expectedRate shouldBe 31.0
        focused.rates shouldBe listOf(62.0)
        focused.concurrency shouldBe listOf(10, 100)
        focused.workspaces shouldBe listOf(10_000)
        focused.cohortShares shouldBe true
        focused.measuredSeconds shouldBe 60
        // A preset: a later flag still overrides a field.
        BenchmarkOptions.parse(listOf("--local", "--focused", "--concurrency", "10")).concurrency shouldBe listOf(10)
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--focused", "--partitioned-variant")) }
            .message shouldContain "CHOSEN only"
    }

    @Test
    fun `a target is required and local excludes a remote one`() {
        shouldThrow<UsageException> { BenchmarkOptions.parse(emptyList()) }.message shouldContain "--local or --jdbc-url"
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--create-database")) }
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--bogus")) }.message shouldContain "unknown flag"
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--concurrency", "0")) }
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--workspaces", "1", "--inboxes-per-workspace", "10")) }
            .message shouldContain "at least 50 inboxes"
    }

    @Test
    fun `the recipient mix draws by weight and is bounded at the edge's 50-recipient cap`() {
        val mix = RecipientMix.parse("1:60,3:25,10:12,50:3")
        mix.draw(0.0) shouldBe 1
        mix.draw(0.59) shouldBe 1
        mix.draw(0.60) shouldBe 3
        mix.draw(0.84) shouldBe 3
        mix.draw(0.85) shouldBe 10
        mix.draw(0.97) shouldBe 50
        mix.draw(0.999) shouldBe 50
        mix.maxRecipients shouldBe 50
        shouldThrow<IllegalArgumentException> { RecipientMix.parse("51:1") }
        shouldThrow<IllegalArgumentException> { RecipientMix.parse("1:0") }
    }
}
