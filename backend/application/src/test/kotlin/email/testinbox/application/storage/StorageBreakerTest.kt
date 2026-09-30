package email.testinbox.application.storage

import email.testinbox.application.storage.StorageBreaker.Admission
import email.testinbox.application.storage.StorageBreaker.Kind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration

/** ADR-035 §8, on a manual clock: no sleeps. */
class StorageBreakerTest {
    private var now = 0L
    private val breaker = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2)) { now }

    private fun advance(by: Duration) {
        now += by.toNanos()
    }

    @Test
    fun `a closed breaker admits everyone and blocks no one`() {
        breaker.admit() shouldBe Admission.Closed
        breaker.isBlocked() shouldBe false
    }

    @Test
    fun `an open breaker blocks until its backoff, then hands out exactly one trial`() {
        breaker.trip(Kind.AMBIGUOUS)
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS))
        breaker.isBlocked() shouldBe true

        advance(Duration.ofSeconds(15))
        breaker.isBlocked() shouldBe false // a check consumes no trial
        breaker.admit() shouldBe Admission.Trial(setOf(Kind.AMBIGUOUS), 1)
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS)) // one trial at a time
        breaker.isBlocked() shouldBe true
    }

    @Test
    fun `a failed trial doubles the backoff up to the cap, and a success resets it`() {
        breaker.trip(Kind.UNAVAILABLE)
        val seen = mutableListOf<Duration>()
        repeat(5) {
            advance(breaker.currentBackoff)
            breaker.admit().shouldBeInstanceOf<Admission.Trial>()
            breaker.trip(Kind.UNAVAILABLE)
            seen += breaker.currentBackoff
        }
        seen shouldBe listOf(30L, 60L, 120L, 120L, 120L).map { Duration.ofSeconds(it) }

        advance(breaker.currentBackoff)
        breaker.admit().shouldBeInstanceOf<Admission.Trial>()
        breaker.close()
        breaker.isOpen shouldBe false
        breaker.currentBackoff shouldBe Duration.ofSeconds(15)
    }

    @Test
    fun `an abandoned trial lets the next caller trial instead, with no new backoff`() {
        breaker.trip(Kind.QUOTA)
        advance(Duration.ofSeconds(15))
        breaker.admit().shouldBeInstanceOf<Admission.Trial>()
        breaker.abandonTrial()
        breaker.admit() shouldBe Admission.Trial(setOf(Kind.QUOTA), 1)
    }

    @Test
    fun `a later kind never hides an earlier one, so the trial must clear both`() {
        breaker.trip(Kind.AMBIGUOUS)
        breaker.trip(Kind.CLOCK_OFFSET) // e.g. a spurious offset reading
        advance(breaker.currentBackoff)

        val trial = breaker.admit()
        trial shouldBe Admission.Trial(setOf(Kind.AMBIGUOUS, Kind.CLOCK_OFFSET), 2)
        (trial as Admission.Trial).needsRealEvent shouldBe false
    }

    @Test
    fun `quota needs a real event as its trial, even alongside another kind`() {
        breaker.trip(Kind.QUOTA)
        breaker.trip(Kind.UNAVAILABLE)
        advance(breaker.currentBackoff)
        (breaker.admit() as Admission.Trial).needsRealEvent shouldBe true
    }

    @Test
    fun `a trial's success never closes a trip that happened while it ran`() {
        breaker.trip(Kind.QUOTA)
        advance(breaker.currentBackoff)
        val trial = breaker.admit() as Admission.Trial // a whole real event, up to E

        breaker.trip(Kind.AMBIGUOUS) // an older event ends ambiguous meanwhile
        breaker.close(trial) // the quota trial succeeds

        breaker.isOpen shouldBe true // the ambiguous trip still needs its own probe
        advance(breaker.currentBackoff)
        breaker.admit() shouldBe Admission.Trial(setOf(Kind.QUOTA, Kind.AMBIGUOUS), 2)
    }

    @Test
    fun `a trial issued for the current trips closes the breaker`() {
        breaker.trip(Kind.UNAVAILABLE)
        advance(breaker.currentBackoff)
        breaker.close(breaker.admit() as Admission.Trial)
        breaker.isOpen shouldBe false
    }
}
