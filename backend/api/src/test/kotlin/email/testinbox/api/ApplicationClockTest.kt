package email.testinbox.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock

/**
 * The clock the API context actually injects, not just `Clocks.utcMicros()`:
 * a nanosecond clock wired back in makes a repeated request disagree with the
 * timestamp PostgreSQL stored for the first one.
 */
class ApplicationClockTest : ApiIntegrationTestBase() {
    @Autowired lateinit var clock: Clock

    @Test
    fun `the wired application clock ticks in whole microseconds, as PostgreSQL stores time`() {
        repeat(10_000) { (clock.instant().nano % 1_000) shouldBe 0 }
    }
}
