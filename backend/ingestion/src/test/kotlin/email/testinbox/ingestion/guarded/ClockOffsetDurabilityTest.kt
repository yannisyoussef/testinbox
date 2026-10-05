package email.testinbox.ingestion.guarded

import email.testinbox.application.port.StorageInspection
import email.testinbox.application.storage.IngestSyncHook
import email.testinbox.application.storage.StorageBreaker
import email.testinbox.ingestion.ops.StorageNodeRuntime
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ADR-035 §5 (TI-STORAGE-003b P1-1): the hold an out-of-bound DB↔storage
 * clock offset imposes is DURABLE. It lives in the reservation rows, so a
 * cleaner (or the whole API process) that restarts after the clocks recover
 * still cannot release a reservation before `write_deadline_at + S + offset`.
 *
 * A "restart" is a new harness on the same database and bucket: a new
 * process with none of the old one's memory. It has no skew: the clocks have
 * recovered.
 */
class ClockOffsetDurabilityTest {
    private val harnesses = mutableListOf<GuardedIngestHarness>()

    @AfterEach
    fun close() = harnesses.reversed().forEach { it.close() }

    private fun track(h: GuardedIngestHarness) = h.also { harnesses += it }

    private val crashBeforeCommit =
        object : IngestSyncHook {
            override fun beforeCommit(): Unit = error("died before T2")
        }

    @Volatile private var skew: Duration = Duration.ZERO

    private val skewed = { real: StorageInspection ->
        object : StorageInspection by real {
            override fun serverTime() = real.serverTime().let { it.copy(date = it.date.plus(skew)) }
        }
    }

    private val settle = Duration.ofMinutes(17)

    /** release_not_before − write_deadline_at, in seconds: what back-dating preserves. */
    private fun hold(h: GuardedIngestHarness): Long =
        h.count("SELECT extract(epoch FROM release_not_before - write_deadline_at)::bigint FROM storage_reservation")

    private fun state(h: GuardedIngestHarness) = h.reservationStates().keys.single()

    /** An event that uploaded everything, then its process died before T2: one reservation, deadline now + E. */
    private fun abandoned(): GuardedIngestHarness {
        val h = track(GuardedIngestHarness(hook = crashBeforeCommit, inspectionOverride = skewed))
        runCatching { h.deliver(listOf(h.inbox(h.workspace()).second)) }
        h.reservationStates() shouldBe mapOf("RESERVED" to 1L)
        return h
    }

    /** A new process on the same database: healthy clocks, empty memory. */
    private fun restarted(h: GuardedIngestHarness) = track(h.restart())

    @Test
    fun `A and B - a RESERVED row held during a skew is still held after a restart with healthy clocks`() {
        val h = abandoned()
        h.backdate(Duration.ofSeconds(110)) // 10 s before its deadline: still RESERVED
        skew = Duration.ofSeconds(45)

        h.cleanup().run().suspendedForClockOffset shouldBe true

        state(h) shouldBe "RESERVED"
        val held = hold(h)
        (held >= settle.seconds + 45) shouldBe true // durable, in the row
        (held <= settle.seconds + 50) shouldBe true // the observed offset plus its error, no more

        val fresh = restarted(h)
        h.backdate(Duration.ofSeconds(10).plus(settle).plusSeconds(20)) // deadline + S + 20 s: the plain rule would release
        fresh.releaseCycle().released shouldBe 0
        state(fresh) shouldBe "RELEASING" // expired, keeping the hold
        hold(fresh) shouldBe held

        h.backdate(Duration.ofSeconds(60)) // now past deadline + S + offset
        fresh.releaseCycle().released shouldBe 1
    }

    @Test
    fun `C - a RELEASING row held during a skew is still held after a restart with healthy clocks`() {
        val h = abandoned()
        h.backdate(Duration.ofSeconds(120).plus(settle).plusSeconds(20)) // deadline + S + 20 s
        h.cleanup().run().released shouldBe 0 // healthy: expires and witnesses, cannot release yet
        state(h) shouldBe "RELEASING"
        hold(h) shouldBe settle.seconds

        skew = Duration.ofSeconds(45)
        h.cleanup().run().suspendedForClockOffset shouldBe true
        val held = hold(h)
        (held >= settle.seconds + 45) shouldBe true

        val fresh = restarted(h)
        fresh.releaseCycle().released shouldBe 0 // the plain deadline + S has passed; the hold has not

        h.backdate(Duration.ofSeconds(60))
        fresh.releaseCycle().released shouldBe 1
    }

    @Test
    fun `D - repeated bad-offset passes hold once, by the largest offset, never compounding`() {
        val h = abandoned()
        skew = Duration.ofSeconds(45)
        val cleanup = h.cleanup()

        repeat(6) { cleanup.run().suspendedForClockOffset shouldBe true }
        val afterSix = hold(h)
        // And across cleaner restarts: a fresh instance each time, no shared memory.
        repeat(6) { h.cleanup().run().suspendedForClockOffset shouldBe true }

        (afterSix <= settle.seconds + 50) shouldBe true // one offset, not six
        (hold(h) - afterSix <= 2) shouldBe true // later passes add only measurement noise, not 45 s each
        h.reservations.holdForClockOffset(Duration.ofSeconds(30), settle) shouldBe 0 // a smaller offset never moves it earlier

        skew = Duration.ofSeconds(90) // a worse episode raises it, to that offset
        cleanup.run().suspendedForClockOffset shouldBe true
        (hold(h) >= settle.seconds + 90) shouldBe true
        (hold(h) <= settle.seconds + 95) shouldBe true
    }

    @Test
    fun `E - with no skew, release times are exactly deadline + S, as before`() {
        val h = abandoned() // skew stays zero
        h.backdate(Duration.ofSeconds(120).plus(settle).plusSeconds(20))

        val first = h.cleanup()
        first.run().suspendedForClockOffset shouldBe false
        hold(h) shouldBe settle.seconds
        h.tick()
        first.run().released shouldBe 1
    }

    @Test
    fun `the ingestion node holds its own reservations durably when it sees the offset first`() {
        val h = abandoned()
        skew = Duration.ofSeconds(45)
        val breaker = StorageBreaker()

        StorageNodeRuntime(h.lifecycle, breaker, h.inspection, h.clock, h.metrics, reservations = h.reservations).checkOffset()

        breaker.isOpen shouldBe true
        (hold(h) >= settle.seconds + 45) shouldBe true // no cleaner had to observe this episode
    }

    /** The real store, whose episode record and/or hold fail while the flags are set. */
    private class FailingHold(
        private val real: email.testinbox.application.port.StorageReservations,
        @Volatile var failRecord: Boolean = true,
        @Volatile var failApply: Boolean = true,
    ) : email.testinbox.application.port.StorageReservations by real {
        var failing: Boolean
            get() = failRecord || failApply
            set(value) {
                failRecord = value
                failApply = value
            }

        @Volatile private var recorded = false

        override fun recordClockEpisode(offset: Duration) {
            if (failRecord) error("database refused the episode record")
            real.recordClockEpisode(offset)
            recorded = true
        }

        // Fails only when there is a recorded episode to apply, as a lock timeout would.
        override fun applyClockEpisode(settle: Duration): Int? =
            if (failApply && recorded) error("lock timeout writing the hold") else real.applyClockEpisode(settle)
    }

    private fun episodes(h: GuardedIngestHarness) = h.count("SELECT count(*) FROM storage_clock_episode")

    /** The process observes the skew, records it, and dies before its hold commits. */
    private fun observeThenDieBeforeHold(h: GuardedIngestHarness) {
        val dying = FailingHold(h.reservations, failRecord = false, failApply = true)
        skew = Duration.ofSeconds(45)
        runCatching { h.cleanup(reservations = dying).run() }.isFailure shouldBe true
        episodes(h) shouldBe 1 // the observation survives the process
        skew = Duration.ZERO // the clocks recover; the cleaner above is never used again
    }

    @Test
    fun `1 - RESERVED - observed, hold failed, process died, clocks recovered - a fresh process cannot release early`() {
        val h = abandoned()
        h.backdate(Duration.ofSeconds(110)) // 10 s before its deadline
        observeThenDieBeforeHold(h)
        state(h) shouldBe "RESERVED"
        h.count("SELECT count(*) FROM storage_reservation WHERE release_not_before IS NOT NULL") shouldBe 0 // no hold written

        val fresh = restarted(h)
        h.backdate(Duration.ofSeconds(10).plus(settle).plusSeconds(20)) // deadline + S + 20 s
        fresh.releaseCycle().released shouldBe 0 // the recorded episode is applied before any release

        episodes(h) shouldBe 0 // applied and forgotten together
        (hold(h) >= settle.seconds + 45) shouldBe true
        h.backdate(Duration.ofSeconds(60))
        fresh.releaseCycle()
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `2 - RELEASING - observed, hold failed, process died, clocks recovered - a fresh process cannot release early`() {
        val h = abandoned()
        h.backdate(Duration.ofSeconds(120).plus(settle).plusSeconds(20)) // deadline + S + 20 s
        h.cleanup().run().released shouldBe 0 // healthy: expires and witnesses
        state(h) shouldBe "RELEASING"
        hold(h) shouldBe settle.seconds
        observeThenDieBeforeHold(h)
        hold(h) shouldBe settle.seconds // the hold never committed

        val fresh = restarted(h)
        fresh.releaseCycle().released shouldBe 0 // plain deadline + S is past; the recorded episode is not

        episodes(h) shouldBe 0
        (hold(h) >= settle.seconds + 45) shouldBe true
        h.backdate(Duration.ofSeconds(60))
        fresh.releaseCycle()
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `5 - once the recorded episode is applied, cleanup and the witness behave normally again`() {
        val h = abandoned()
        observeThenDieBeforeHold(h)
        val fresh = restarted(h)
        val cleanup = fresh.cleanup()

        val first = cleanup.run() // applies the episode, then an ordinary pass
        first.suspendedForClockOffset shouldBe false
        first.witnessed shouldBe true
        episodes(h) shouldBe 0
        val held = hold(h)
        repeat(3) {
            fresh.tick()
            cleanup.run().suspendedForClockOffset shouldBe false
        }
        hold(h) shouldBe held // a healthy pass never touches the hold again
        h.backdate(Duration.ofSeconds(120).plus(settle).plusSeconds(60)) // past deadline + S + offset
        fresh.releaseCycle(cleanup)
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `a hold that cannot be written is never forgotten - nothing is released until it is`() {
        val h = abandoned()
        h.backdate(Duration.ofSeconds(120).plus(settle).plusSeconds(20)) // deadline + S + 20 s
        val store = FailingHold(h.reservations)
        val cleanup = h.cleanup(reservations = store)

        skew = Duration.ofSeconds(45)
        runCatching { cleanup.run() }.isFailure shouldBe true // observed, but the hold could not be written
        skew = Duration.ZERO // the clocks recover...
        repeat(2) {
            h.tick()
            runCatching { cleanup.run() }.isFailure shouldBe true // ...and still nothing is released
        }
        h.reservationStates() shouldBe mapOf("RELEASING" to 1L) // expired, still charged

        store.failing = false
        cleanup.run().released shouldBe 0 // the pending hold is written first
        (hold(h) >= settle.seconds + 45) shouldBe true
        h.tick()
        cleanup.run().released shouldBe 0 // past deadline + S, not past the hold
        h.backdate(Duration.ofSeconds(60))
        h.releaseCycle(cleanup) // this cleaner already holds an old enough witness: either pass may release
        h.reservationStates() shouldBe emptyMap()
    }

    @Test
    fun `the ingestion node keeps its breaker open until its own hold is written`() {
        val h = abandoned()
        val store = FailingHold(h.reservations)
        val breaker = StorageBreaker(Duration.ofMillis(1), Duration.ofMillis(1))
        val node = StorageNodeRuntime(h.lifecycle, breaker, h.inspection, h.clock, h.metrics, reservations = store)

        skew = Duration.ofSeconds(45)
        node.checkOffset()
        skew = Duration.ZERO
        Thread.sleep(5) // past the breaker's backoff
        node.checkOffset() // in bound, but the hold is still unwritten: tripped again
        breaker.isBlocked() shouldBe true
        h.count("SELECT count(*) FROM storage_reservation WHERE release_not_before IS NOT NULL") shouldBe 0

        store.failing = false
        node.checkOffset()
        (hold(h) >= settle.seconds + 45) shouldBe true
    }
}
