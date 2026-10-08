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
    private val breaker = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })

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

    // --- STORAGE_FULL (filesystem-containment contract §8, TI-STORAGE-006E) ---------------------

    private var evidence = false
    private var evidenceChecks = 0
    private lateinit var full: StorageBreaker

    init {
        full =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate =
                    StorageFullGate.of {
                        // The evidence reads the database: never while holding the breaker's lock.
                        Thread.holdsLock(full) shouldBe false
                        evidenceChecks++
                        evidence
                    },
            )
    }

    @Test
    fun `a full filesystem stays shut without evidence, and consumes no trial while it waits`() {
        full.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(10)) // far past any backoff: a timer never reopens it

        repeat(3) { full.admit() shouldBe Admission.Blocked(setOf(Kind.STORAGE_FULL)) }
        full.isBlocked() shouldBe true

        evidence = true // a fresh observation shows R_ops available
        val trial = full.admit()
        trial.shouldBeInstanceOf<Admission.Trial>()
        trial.needsRealEvent shouldBe true // a zero-byte probe succeeds on a full filesystem
        full.admit() shouldBe Admission.Blocked(setOf(Kind.STORAGE_FULL)) // one trial at a time
        val checks = evidenceChecks
        full.isBlocked() shouldBe true
        evidenceChecks shouldBe checks // a trial in flight: no database read
        full.openKinds shouldBe setOf(Kind.STORAGE_FULL)
    }

    @Test
    fun `every STORAGE_FULL trip, a failed trial included, tells the gate`() {
        var trips = 0
        val counted =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate =
                    object : StorageFullGate {
                        override fun tripped() {
                            trips++
                        }

                        override fun evidence() = true
                    },
            )
        counted.trip(Kind.AMBIGUOUS)
        trips shouldBe 0
        counted.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(1))
        counted.admit().shouldBeInstanceOf<Admission.Trial>()
        counted.trip(Kind.STORAGE_FULL) // the trial's real event hit ENOSPC again
        trips shouldBe 2
    }

    @Test
    fun `the evidence is read only when a storage-full trial could be due`() {
        full.admit() shouldBe Admission.Closed
        full.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        full.admit().shouldBeInstanceOf<Admission.Trial>()
        evidenceChecks shouldBe 0 // no STORAGE_FULL kind, no database read

        full.trip(Kind.STORAGE_FULL)
        full.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS, Kind.STORAGE_FULL)) // backoff first
        evidenceChecks shouldBe 0
        advance(Duration.ofMinutes(1))
        full.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS, Kind.STORAGE_FULL))
        evidenceChecks shouldBe 1
    }

    @Test
    fun `an evidence check that fails counts as no evidence`() {
        val failing =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate = StorageFullGate.of { error("database down") },
            )
        failing.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(1))
        failing.admit() shouldBe Admission.Blocked(setOf(Kind.STORAGE_FULL))
        failing.isBlocked() shouldBe true
    }

    @Test
    fun `by default there is never evidence - only a restart clears a full filesystem without a monitor`() {
        val plain = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        plain.trip(Kind.STORAGE_FULL)
        advance(Duration.ofHours(1))
        plain.admit() shouldBe Admission.Blocked(setOf(Kind.STORAGE_FULL))
    }

    // --- trip generations under concurrency (owner review §6) --------------------------------------

    @Test
    fun `evidence read for one trip never authorizes a trial after a newer trip, even once the backoff has passed`() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        lateinit var gated: StorageBreaker
        gated =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate =
                    StorageFullGate.of {
                        reading.countDown()
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        true // evidence that was valid for the trip it was read for
                    },
            )
        gated.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(1))
        val pool =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val admission = pool.submit<Admission> { gated.admit() }
            reading.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
            gated.trip(Kind.STORAGE_FULL) // a newer trip lands while the evidence is being read
            advance(Duration.ofMinutes(10)) // and its backoff passes too: only the generation can refuse
            release.countDown()
            admission.get(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe Admission.Blocked(setOf(Kind.STORAGE_FULL))
        } finally {
            pool.shutdownNow()
        }
        // A fresh read for the new generation does authorize it.
        gated.admit().shouldBeInstanceOf<Admission.Trial>()
    }

    @Test
    fun `many callers racing on one generation's evidence get exactly one trial`() {
        val gated =
            StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now }, storageFullGate = StorageFullGate.of { true })
        gated.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(1))
        val start = java.util.concurrent.CountDownLatch(1)
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(8)
        try {
            val results =
                (1..32).map {
                    pool.submit<Admission> {
                        start.await()
                        gated.admit()
                    }
                }
            start.countDown()
            results.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.count { it is Admission.Trial } shouldBe 1
        } finally {
            pool.shutdownNow()
        }
    }
}
