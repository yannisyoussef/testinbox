package email.testinbox.application

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The application clock never yields an instant PostgreSQL would round: a
 * value written and returned in one request must equal every later read of it
 * (the Linux JVM ticks in nanoseconds; macOS hid the defect locally).
 */
class ClocksTest {
    @Test
    fun `the application clock ticks in whole microseconds`() {
        val clock = Clocks.utcMicros()
        repeat(10_000) { (clock.instant().nano % 1_000) shouldBe 0 }
        clock.zone shouldBe java.time.ZoneOffset.UTC
    }
}
