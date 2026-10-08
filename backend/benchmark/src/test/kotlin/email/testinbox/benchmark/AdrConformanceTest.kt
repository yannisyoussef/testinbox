package email.testinbox.benchmark

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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
        only(conforming.copy(expectedRate = 100.0)) shouldContain "expected rate 100.0 is not §11's 260.0"
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
