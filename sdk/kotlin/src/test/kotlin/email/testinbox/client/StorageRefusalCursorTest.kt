package email.testinbox.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * The ADR-035 §13c observation cursor is an atomic `max`. The primitive is
 * tested on its own, under real thread contention, and a last-writer-wins
 * mutant of the same shape is shown to fail the same assertion — so the test
 * is known to be able to detect the regression it guards against.
 */
class StorageRefusalCursorTest {
    @Test
    fun `seeded from the representation, advanced by max, never backwards`() {
        val cursor = StorageRefusalCursor(5)
        assertEquals(5L, cursor.current)
        assertEquals(8L, cursor.advance(8))
        assertEquals(8L, cursor.advance(4))
        assertEquals(8L, cursor.current)
        assertEquals(12L, cursor.advance(12))
        assertEquals(12L, cursor.advance(9))
        assertEquals(12L, cursor.current)
    }

    @Test
    fun `an unavailable cursor is null, is seeded by the first explicit value, and the sentinel never leaks`() {
        val cursor = StorageRefusalCursor(null)
        assertNull(cursor.current)
        assertEquals(0L, cursor.advance(0))
        assertEquals(0L, cursor.current)
        assertThrows(IllegalArgumentException::class.java) { cursor.advance(-1) }
    }

    /** Many threads, each advancing random counts: the result is the maximum, every time. */
    @RepeatedTest(5)
    fun `concurrent advances from many threads leave exactly the maximum`() {
        val cursor = StorageRefusalCursor(0)
        val expected = hammer { cursor.advance(it) }
        assertEquals(expected, cursor.current)
    }

    @Test
    fun `the detector detects - a read-compare-write mutant loses the maximum under the same contention`() {
        // The shape a careless implementation would have: no atomicity between
        // the read and the write. Run until it is caught losing an update; a
        // bounded number of rounds, so a lucky interleaving cannot make the
        // mutant look correct.
        var caught = false
        repeat(50) {
            if (caught) return@repeat
            val mutant = LastWriterWinsCursor(0)
            val expected = hammer { mutant.advance(it) }
            if (mutant.current != expected) caught = true
        }
        assertTrue(caught, "the last-writer-wins mutant was never caught losing the maximum; the contention harness is too weak")
    }

    /** Drives [advance] from 8 threads with values that interleave, returning the maximum handed in. */
    private fun hammer(advance: (Long) -> Unit): Long {
        val threads = 8
        val perThread = 2_000
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        var max = 0L
        try {
            val tasks =
                (0 until threads).map { t ->
                    pool.submit {
                        start.await()
                        for (i in 0 until perThread) {
                            // Interleaved ascending ramps: every thread keeps producing
                            // values both above and below the others' current values.
                            advance(((i.toLong() * threads) + t) % 1_024)
                        }
                    }
                }
            max = 1_023
            start.countDown()
            tasks.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return max
    }

    /** NEVER production code: the non-atomic shape the real cursor must not have. */
    private class LastWriterWinsCursor(initial: Long) {
        private val value = AtomicLong(initial)
        val current: Long get() = value.get()

        fun advance(candidate: Long) {
            val have = value.get() // read
            if (candidate > have) { // compare
                Thread.yield()
                value.set(candidate) // plain write: a concurrent larger value is lost
            }
        }
    }
}
