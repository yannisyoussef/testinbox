package email.testinbox.benchmark

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

/** §11's constants are not loosenable through flags: every departure is named, and any one of them makes the run non-evidence. */
class AdrConformanceTest {
    /** A remote run with the defaults and a host class: exactly §11. */
    private val conforming = BenchmarkOptions.parse(listOf("--jdbc-url", "jdbc:postgresql://db/ti_bench", "--host-class", "staging-vm"))

    private fun only(options: BenchmarkOptions): String = AdrConformance.reasons(options).single()

    @Test
    fun `the section 11 defaults on a remote target with a host class are ADR evidence`() {
        AdrConformance.reasons(conforming).shouldBeEmpty()
    }

    @Test
    fun `each departure is reported on its own`() {
        // Amendment D: R is a parameter, so a departure is a matrix that does not offer R and 2R.
        only(conforming.copy(expectedRate = 100.0)) shouldContain "offered rates [260.0, 520.0] do not include §11's [100.0, 200.0]"
        only(conforming.copy(rates = listOf(260.0))) shouldContain "do not include §11's [260.0, 520.0]"
        only(conforming.copy(rates = listOf(520.0, 1040.0))) shouldContain "do not include"
        only(conforming.copy(concurrency = listOf(1, 10, 25, 50))) shouldContain "does not include §11's [1, 10, 25, 50, 100]"
        only(conforming.copy(reservationBacklog = 999)) shouldContain "reservation backlog 999 is below §11's 1000"
        only(conforming.copy(deltaBacklog = 499)) shouldContain "delta backlog 499 is below one compaction interval's worth (500)"
        only(conforming.copy(workspaces = listOf(200, 9_999))) shouldContain "no population of >= 10000 workspaces"
        only(conforming.copy(local = true, jdbcUrl = null)) shouldContain "--local"
        only(conforming.copy(hostClass = "unspecified")) shouldContain "--host-class was not supplied"
        only(conforming.copy(reference = false)) shouldContain "--no-reference"
        only(conforming.copy(referenceMode = ReferenceMode.CEILING_OFF)) shouldContain "CEILING_OFF is not the no-ceiling mode c (NO_LOCK)"
    }

    @Test
    fun `the closed-beta rate of amendment D, offered at R and 2R, is ADR evidence`() {
        // R = r × (30 + L) = 1 × 31 (owner decision 2026-11-06). Whether 31 is the RIGHT R is the
        // activation gate's question: it refuses evidence whose rate differs from the declared R_gated.
        AdrConformance.reasons(conforming.copy(expectedRate = 31.0, rates = listOf(31.0, 62.0))).shouldBeEmpty()
        only(conforming.copy(expectedRate = 31.0, rates = listOf(31.0))) shouldContain "do not include §11's [31.0, 62.0]"
    }

    @Test
    fun `a population without the cohort shares, and a focused run, are not gate evidence`() {
        // Owner decision 2026-11-06 (#124 6684712608): the gate population includes the beta's 31 shares.
        only(conforming.copy(cohortShares = false)) shouldContain "--no-cohort-shares"
        AdrConformance.reasons(conforming.copy(focused = true)) shouldContain "--focused: a focused A′ measurement, never gate evidence"
        AdrConformance
            .reasons(
                BenchmarkOptions.parse(listOf("--jdbc-url", "jdbc:postgresql://db/ti_bench", "--host-class", "h", "--focused")),
            ).any { it.startsWith("--focused") } shouldBe true
    }

    @Test
    fun `a closed cohort smaller than the beta's thirty participants is never gate evidence`() {
        only(conforming.copy(cohortParticipants = 10)) shouldContain
            "--cohort-participants 10: a closed cohort of 11 workspaces, not the beta's 30 participants + 1 legacy"
        AdrConformance.reasons(conforming.copy(cohortParticipants = 30)).shouldBeEmpty()
        val run =
            AdrConformance.reasons(
                BenchmarkOptions.parse(
                    listOf(
                        "--jdbc-url",
                        "jdbc:postgresql://db/ti_bench",
                        "--host-class",
                        "h",
                        "--focused-cohort",
                        "--cohort-participants",
                        "15",
                    ),
                ),
            )
        run.any { it.startsWith("--focused") } shouldBe true
        run.any { it.startsWith("--cohort-participants 15") } shouldBe true
    }

    @Test
    fun `a pool other than the harness's c + 6 is never gate evidence`() {
        only(conforming.copy(poolSize = 8)) shouldContain "--pool-size 8: not §11's c + 6 pool; never gate evidence"
    }

    @Test
    fun `gate evidence is faithful to production's pool lifecycle - cold, with production's maxLifetime`() {
        // The default IS production's lifecycle; naming it explicitly changes nothing.
        AdrConformance.reasons(conforming.copy(coldPool = true, poolMaxLifetime = Duration.ofMinutes(30))).shouldBeEmpty()
        only(conforming.copy(coldPool = false)) shouldContain "--warm-pool: every connection was warmed past pgJDBC's prepareThreshold"
        only(conforming.copy(poolMaxLifetime = Duration.ofSeconds(60))) shouldContain "--pool-max-lifetime PT1M is not production's PT30M"
        only(conforming.copy(poolMaxLifetime = Duration.ofHours(1))) shouldContain "is not production's PT30M"
        AdrConformance.reasons(conforming.copy(coldPool = false, poolMaxLifetime = Duration.ofSeconds(60))).size shouldBe 2
    }

    @Test
    fun `a superset of the matrix still conforms`() {
        AdrConformance
            .reasons(
                conforming.copy(
                    rates = listOf(130.0, 260.0, 520.0, 1040.0),
                    concurrency = listOf(1, 10, 25, 50, 100, 200),
                    workspaces = listOf(200, 10_000),
                    reservationBacklog = 5_000,
                    deltaBacklog = 2_000,
                ),
            ).shouldBeEmpty()
    }

    @Test
    fun `several departures are all listed`() {
        AdrConformance
            .reasons(
                BenchmarkOptions.parse(listOf("--local", "--workspaces", "50", "--concurrency", "1,10", "--rates", "50")),
            ).size shouldBe
            5
    }
}
