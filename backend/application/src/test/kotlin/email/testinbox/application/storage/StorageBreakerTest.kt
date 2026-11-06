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
        breaker.abandonTrial(breaker.admit().shouldBeInstanceOf<Admission.Trial>())
        breaker.admit() shouldBe Admission.Trial(setOf(Kind.QUOTA), 1)
    }

    @Test
    fun `a later kind never hides an earlier one, so the trial must clear both`() {
        breaker.trip(Kind.UNAVAILABLE)
        breaker.trip(Kind.CLOCK_OFFSET) // e.g. a spurious offset reading
        advance(breaker.currentBackoff)

        val trial = breaker.admit()
        trial shouldBe Admission.Trial(setOf(Kind.UNAVAILABLE, Kind.CLOCK_OFFSET), 2)
        // Neither kind lets a zero-byte probe lie (an ambiguous upload does: issue #102).
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

    // --- an ambiguous upload is a possibly full filesystem (issue #102, E9) ------------------------

    @Test
    fun `an ambiguous trip needs a real event as its trial, never the witness alone`() {
        val plain = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        plain.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        val trial = plain.admit()
        trial.shouldBeInstanceOf<Admission.Trial>()
        trial.needsRealEvent shouldBe true
        plain.isEscalated shouldBe false
    }

    @Test
    fun `under an enforcing mode an ambiguous trip waits for evidence from its first trip, consuming no trial`() {
        var checks = 0
        var evidence = false
        val enforcing =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate =
                    StorageFullGate.of {
                        checks++
                        evidence
                    },
                ambiguityNeedsEvidence = true,
            )
        enforcing.trip(Kind.AMBIGUOUS)
        advance(Duration.ofHours(1)) // no timer reopens it
        repeat(3) { enforcing.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS)) }
        enforcing.isBlocked() shouldBe true
        checks shouldBe 4

        evidence = true // a fresh observation shows R_ops available
        val trial = enforcing.admit()
        trial.shouldBeInstanceOf<Admission.Trial>()
        trial.needsRealEvent shouldBe true
        enforcing.close(trial)
        enforcing.isOpen shouldBe false
    }

    @Test
    fun `under an enforcing mode every ambiguous trip tells the gate, so only a later observation counts`() {
        var trips = 0
        val enforcing =
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
                ambiguityNeedsEvidence = true,
            )
        enforcing.trip(Kind.AMBIGUOUS)
        trips shouldBe 1
        advance(Duration.ofMinutes(1))
        enforcing.admit().shouldBeInstanceOf<Admission.Trial>()
        enforcing.trip(Kind.AMBIGUOUS, fromTrialEvent = true) // the trial's real event was ambiguous again
        trips shouldBe 2
    }

    @Test
    fun `under OFF the first ambiguous trial is witness plus a real event, and a second ambiguity escalates to evidence`() {
        var evidence = false
        val lenient =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                storageFullGate = StorageFullGate.of { evidence },
            )
        lenient.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        val first = lenient.admit()
        first.shouldBeInstanceOf<Admission.Trial>() // one real-event trial without evidence
        lenient.trip(Kind.AMBIGUOUS, fromTrialEvent = true) // ...and it came back ambiguous: a store that swallows large writes
        lenient.isEscalated shouldBe true

        advance(Duration.ofHours(1))
        lenient.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS)) // no second recovery without evidence
        lenient.isBlocked() shouldBe true

        evidence = true
        val second = lenient.admit()
        second.shouldBeInstanceOf<Admission.Trial>()
        lenient.close(second)
        lenient.isEscalated shouldBe false // a close resets the escalation
        lenient.isOpen shouldBe false
    }

    @Test
    fun `without a monitor an escalated ambiguity clears only with a restart, like a full filesystem`() {
        val plain = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        plain.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        plain.admit().shouldBeInstanceOf<Admission.Trial>()
        plain.trip(Kind.AMBIGUOUS, fromTrialEvent = true)
        advance(Duration.ofDays(1))
        plain.admit() shouldBe Admission.Blocked(setOf(Kind.AMBIGUOUS))
    }

    @Test
    fun `a failed witness or a concurrent event ending ambiguous during the trial never escalates`() {
        val plain = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        plain.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        plain.admit().shouldBeInstanceOf<Admission.Trial>()
        plain.trip(Kind.AMBIGUOUS) // the witness failed (storage restarting), or a concurrent older event ended ambiguous
        plain.isEscalated shouldBe false
        advance(Duration.ofMinutes(1))
        plain.admit().shouldBeInstanceOf<Admission.Trial>() // recovers by witness and a real event at the next backoff
    }

    @Test
    fun `a non-ambiguous trip during an ambiguous trial does not escalate`() {
        val plain = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        plain.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        plain.admit().shouldBeInstanceOf<Admission.Trial>()
        plain.trip(Kind.UNAVAILABLE) // storage went away mid-trial: a witness can prove it back
        plain.isEscalated shouldBe false
        advance(Duration.ofMinutes(1))
        plain.admit().shouldBeInstanceOf<Admission.Trial>()
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

    // --- the pre-resolution gate and the non-tenant proof (ADR-025, 2026-11-06) ---------------------

    @Test
    fun `a proof that passes for a real-event kind hands the prover the claim, and the gate one claim at a time`() {
        breaker.trip(Kind.QUOTA)
        advance(breaker.currentBackoff)
        val proof = breaker.admit() as Admission.Trial
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA)) // the proof is still running

        breaker.proven(proof) shouldBe Admission.Proven(proof) // the prover keeps it as its real-event claim
        breaker.isOpen shouldBe true // a real event still has to confirm it (ADR-035 §8)
        breaker.awaitsRealEvent shouldBe true
        repeat(3) { breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA)) } // everyone else, at the gate

        breaker.abandonTrial(proof) // that event stored nothing: the claim goes back, the proof still stands
        val next = breaker.admit()
        next shouldBe Admission.Proven(Admission.Trial(setOf(Kind.QUOTA), 1)) // no second proof
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA))
        breaker.close((next as Admission.Proven).trial)
        breaker.isOpen shouldBe false
        breaker.awaitsRealEvent shouldBe false
    }

    @Test
    fun `a failed real-event trial after the proof is a failed trial - doubled backoff, and the gate shuts again`() {
        breaker.trip(Kind.QUOTA)
        advance(breaker.currentBackoff)
        val claim = (breaker.proven(breaker.admit() as Admission.Trial) as Admission.Proven).trial
        breaker.trip(Kind.QUOTA, claim, realEvent = true)
        breaker.currentBackoff shouldBe Duration.ofSeconds(30)
        breaker.awaitsRealEvent shouldBe false
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA))
    }

    @Test
    fun `kinds a probe proves close on the proof alone, DENIED among them`() {
        breaker.trip(Kind.DENIED)
        breaker.trip(Kind.CLOCK_OFFSET)
        advance(breaker.currentBackoff)
        val proof = breaker.admit() as Admission.Trial
        proof.needsRealEvent shouldBe false
        breaker.proven(proof) shouldBe Admission.Closed
        breaker.isOpen shouldBe false
    }

    @Test
    fun `a proof overtaken by a trip admits nothing, and leaves the newer trip alone`() {
        breaker.trip(Kind.UNAVAILABLE)
        advance(breaker.currentBackoff)
        val proof = breaker.admit() as Admission.Trial
        breaker.trip(Kind.DENIED) // a heartbeat or an older event tripped while the witness ran
        breaker.proven(proof) shouldBe Admission.Blocked(setOf(Kind.UNAVAILABLE, Kind.DENIED))
        breaker.openKinds shouldBe setOf(Kind.UNAVAILABLE, Kind.DENIED)
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.UNAVAILABLE, Kind.DENIED))
    }

    @Test
    fun `a STORAGE_FULL proof reads its evidence, and once proven reads it no more`() {
        full.trip(Kind.STORAGE_FULL)
        advance(Duration.ofMinutes(1))
        evidence = true
        val claim = (full.proven(full.admit() as Admission.Trial) as Admission.Proven).trial
        full.abandonTrial(claim)
        val checks = evidenceChecks
        full.admit().shouldBeInstanceOf<Admission.Proven>()
        evidenceChecks shouldBe checks // no database read per event while the real event is awaited
    }

    // --- trials are bound to their generation (review of 95d3cea: two trials must never run at once) ---

    /** A quota trip whose proof passed, and whose real-event claim is now uploading. */
    private fun realTrialInFlight(): Admission.Trial {
        breaker.trip(Kind.QUOTA)
        advance(Duration.ofSeconds(16))
        val proof = breaker.admit().shouldBeInstanceOf<Admission.Trial>()
        return (breaker.proven(proof) as Admission.Proven).trial
    }

    @Test
    fun `a stale real-event trial ending in abandon never frees a newer proof's slot`() {
        val old = realTrialInFlight()
        breaker.trip(Kind.CLOCK_OFFSET) // from elsewhere, while the real event is still uploading
        advance(Duration.ofSeconds(31))
        breaker.admit().shouldBeInstanceOf<Admission.Trial>() // the new generation's proof, in flight
        breaker.abandonTrial(old) // the OLD real event stored nothing and ends its stale trial
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA, Kind.CLOCK_OFFSET)) // never a second trial
    }

    @Test
    fun `a stale real-event trial closing successfully never frees the newer proof's slot, nor closes the breaker`() {
        val old = realTrialInFlight()
        breaker.trip(Kind.CLOCK_OFFSET)
        advance(Duration.ofSeconds(31))
        breaker.admit().shouldBeInstanceOf<Admission.Trial>()
        breaker.close(old) // a stale success
        breaker.isOpen shouldBe true
        breaker.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA, Kind.CLOCK_OFFSET))
    }

    @Test
    fun `a stale trial's failure is not the newer trial's - no doubled backoff and no escalation`() {
        val ambiguous = StorageBreaker(Duration.ofSeconds(15), Duration.ofMinutes(2), nanoTime = { now })
        ambiguous.trip(Kind.AMBIGUOUS)
        advance(Duration.ofSeconds(15))
        val old = (ambiguous.proven(ambiguous.admit() as Admission.Trial) as Admission.Proven).trial
        ambiguous.trip(Kind.CLOCK_OFFSET) // the old real event is still uploading
        advance(ambiguous.currentBackoff)
        val newer = ambiguous.admit().shouldBeInstanceOf<Admission.Trial>()
        val backoff = ambiguous.currentBackoff

        ambiguous.trip(Kind.AMBIGUOUS, old, realEvent = true) // the OLD upload comes back ambiguous again
        ambiguous.isEscalated shouldBe false
        ambiguous.currentBackoff shouldBe backoff
        // The trip itself still counts (a new generation): the newer proof is stale now, and ends as such.
        ambiguous.proven(newer).shouldBeInstanceOf<Admission.Blocked>()
    }

    // --- the claim lease, L_claim (review of c292340) ------------------------------------------------

    @Test
    fun `L_claim is derived from W_slot, the T1 lock timeout, E and a margin - 3 min as shipped`() {
        StorageProtocol.CLAIM_LEASE shouldBe Duration.ofMinutes(3)
        StorageProtocol.claimLease(Duration.ofSeconds(10), Duration.ofSeconds(5)) shouldBe
            Duration.ofSeconds(10 + 5).plus(StorageProtocol.WRITE_WINDOW).plus(StorageProtocol.CLAIM_MARGIN)
    }

    @Test
    fun `a claim held past its lease is a failed trial - the next caller proves after the backoff, and the old end is stale`() {
        var expiries = 0
        val leased =
            StorageBreaker(
                Duration.ofSeconds(15),
                Duration.ofMinutes(2),
                nanoTime = { now },
                claimLease = Duration.ofMinutes(3),
                onClaimExpired = { expiries++ },
            )
        leased.trip(Kind.QUOTA)
        advance(Duration.ofSeconds(15))
        val stuck = (leased.proven(leased.admit() as Admission.Trial) as Admission.Proven).trial // then hangs

        advance(Duration.ofMinutes(3).minusMillis(1))
        leased.isBlocked() shouldBe true
        leased.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA)) // still within the lease
        expiries shouldBe 0

        advance(Duration.ofMillis(1))
        leased.isBlocked() shouldBe false // an expired claim blocks nobody: the next admit ends it
        leased.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA)) // ended as a failed trial: backoff first
        expiries shouldBe 1
        leased.currentBackoff shouldBe Duration.ofSeconds(30)
        leased.awaitsRealEvent shouldBe false
        leased.openKinds shouldBe setOf(Kind.QUOTA) // no new kind

        // The stuck claimant's late end touches nothing.
        leased.abandonTrial(stuck)
        leased.close(stuck)
        leased.isOpen shouldBe true
        leased.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA))

        advance(Duration.ofSeconds(30))
        val proof = leased.admit()
        proof shouldBe Admission.Trial(setOf(Kind.QUOTA), 2) // a new generation: the proof again
        leased.abandonTrial(stuck) // still stale: the new proof keeps its slot
        leased.admit() shouldBe Admission.Blocked(setOf(Kind.QUOTA))
        leased.trip(Kind.QUOTA, stuck, realEvent = true) // its upload finally fails: not the new trial's failure
        leased.currentBackoff shouldBe Duration.ofSeconds(30)
        expiries shouldBe 1
    }
}
