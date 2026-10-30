package email.testinbox.application

import java.time.Clock
import java.time.Duration

/**
 * The application's wall clock, at the precision the database keeps.
 *
 * PostgreSQL stores `timestamptz` in whole microseconds and ROUNDS a finer
 * value, while the JVM clock on Linux ticks in nanoseconds. An instant a use
 * case returns straight after writing it would then differ from the same row
 * read back (`…619167687Z` answered once, `…619168Z` ever after), and a
 * retried or repeated request would see the resource change. Ticking in
 * microseconds makes the written value, the returned value and every later
 * read identical, on every platform.
 */
object Clocks {
    private val MICROSECOND: Duration = Duration.ofNanos(1_000)

    fun utcMicros(): Clock = Clock.tick(Clock.systemUTC(), MICROSECOND)
}
