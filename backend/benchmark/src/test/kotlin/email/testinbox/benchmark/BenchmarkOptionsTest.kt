package email.testinbox.benchmark

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

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
    fun `--cohort-participants sizes the --focused-cohort closed cohort, 1 to 30, and nothing else`() {
        BenchmarkOptions.parse(listOf("--local")).cohortParticipants shouldBe 30
        BenchmarkOptions.parse(listOf("--local", "--focused-cohort")).cohortParticipants shouldBe 30
        val ten = BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants", "10"))
        ten.cohortParticipants shouldBe 10
        ten.focusedCohort shouldBe true
        // Either order: the preset keeps a value given before it.
        BenchmarkOptions.parse(listOf("--local", "--cohort-participants", "15", "--focused-cohort")).cohortParticipants shouldBe 15
        // One participant + legacy needs 25 inboxes each for a 50-recipient event.
        BenchmarkOptions
            .parse(listOf("--local", "--focused-cohort", "--cohort-participants", "1", "--inboxes-per-workspace", "25"))
            .cohortParticipants shouldBe 1
        BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants", "30")).cohortParticipants shouldBe 30

        // Only with --focused-cohort, even at its default value.
        listOf(
            listOf("--local", "--cohort-participants", "10"),
            listOf("--local", "--cohort-participants", "30"),
            listOf("--local", "--focused", "--cohort-participants", "10"),
            listOf("--local", "--partitioned-variant", "--cohort-participants", "10"),
        ).forEach { args ->
            shouldThrow<UsageException> { BenchmarkOptions.parse(args) }.message shouldContain "--focused-cohort"
        }
        shouldThrow<UsageException> {
            BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants", "31"))
        }.message shouldContain "1..30"
        listOf("0", "-1", "ten").forEach { value ->
            shouldThrow<UsageException> {
                BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants", value))
            }.message shouldContain "positive integer"
        }
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants")) }
            .message shouldContain "needs a value"
    }

    @Test
    fun `--pool-size is a focused run's, 1 to 200`() {
        BenchmarkOptions.parse(listOf("--local", "--focused")).poolSize shouldBe null
        BenchmarkOptions.parse(listOf("--local", "--focused", "--pool-size", "8")).poolSize shouldBe 8
        BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--pool-size", "1")).poolSize shouldBe 1
        BenchmarkOptions.parse(listOf("--local", "--pool-size", "200", "--focused-cohort")).poolSize shouldBe 200
        listOf(
            listOf("--local", "--pool-size", "8"),
            listOf("--jdbc-url", "jdbc:postgresql://db/ti_bench", "--pool-size", "8"),
            listOf("--local", "--partitioned-variant", "--pool-size", "8"),
        ).forEach { args ->
            shouldThrow<UsageException> { BenchmarkOptions.parse(args) }.message shouldContain "--focused or --focused-cohort"
        }
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--focused", "--pool-size", "201")) }
            .message shouldContain "1..200"
        listOf("0", "-3", "eight").forEach { value ->
            shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--focused", "--pool-size", value)) }
                .message shouldContain "positive integer"
        }
    }

    @Test
    fun `the closed cohort's N + 1 workspaces must hold the largest event`() {
        // (10 + 1) × 5 = 55 >= 50; (10 + 1) × 4 = 44 < 50.
        BenchmarkOptions
            .parse(listOf("--local", "--focused-cohort", "--cohort-participants", "10", "--inboxes-per-workspace", "5"))
            .inboxesPerWorkspace shouldBe 5
        shouldThrow<UsageException> {
            BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--cohort-participants", "10", "--inboxes-per-workspace", "4"))
        }.message shouldContain "closed cohort needs at least 50 inboxes"
        // At 30 participants, 2 inboxes each (62) still suffice, as before.
        BenchmarkOptions.parse(listOf("--local", "--focused-cohort", "--inboxes-per-workspace", "2")).inboxesPerWorkspace shouldBe 2
    }

    @Test
    fun `the pool is cold with production's lifetime unless told otherwise`() {
        val defaults = BenchmarkOptions.parse(listOf("--local"))
        defaults.coldPool shouldBe true
        defaults.poolMaxLifetime shouldBe null
        defaults.effectivePoolMaxLifetime shouldBe Duration.ofMinutes(30)
        BenchmarkOptions.parse(listOf("--local", "--cold-pool")).coldPool shouldBe true
        BenchmarkOptions.parse(listOf("--local", "--warm-pool")).coldPool shouldBe false
        // --focused keeps the default: a focused run is cold unless it asks for a warm pool.
        BenchmarkOptions.parse(listOf("--local", "--focused")).coldPool shouldBe true
        BenchmarkOptions.parse(listOf("--local", "--focused", "--warm-pool")).coldPool shouldBe false
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--cold-pool", "--warm-pool")) }
            .message shouldContain "contradict"
    }

    @Test
    fun `--pool-max-lifetime takes a duration of at least Hikari's 30 s minimum`() {
        val short = BenchmarkOptions.parse(listOf("--local", "--focused", "--pool-max-lifetime", "60s"))
        short.poolMaxLifetime shouldBe Duration.ofSeconds(60)
        short.effectivePoolMaxLifetime shouldBe Duration.ofSeconds(60)
        BenchmarkOptions.parse(listOf("--local", "--pool-max-lifetime", "30s")).effectivePoolMaxLifetime shouldBe Duration.ofSeconds(30)
        BenchmarkOptions.parse(listOf("--local", "--pool-max-lifetime", "PT30M")).effectivePoolMaxLifetime shouldBe Duration.ofMinutes(30)
        // Below 30 s Hikari would silently use 30 min: refused, never passed on. 0 (Hikari's "forever") likewise.
        listOf("29s", "29999ms", "0").forEach { value ->
            shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--pool-max-lifetime", value)) }
                .message shouldContain "at least 30 s"
        }
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--pool-max-lifetime")) }.message shouldContain
            "needs a value"
        shouldThrow<UsageException> { BenchmarkOptions.parse(listOf("--local", "--pool-max-lifetime", "soon")) }
            .message shouldContain "not a duration"
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
