package email.testinbox.application.storage

import email.testinbox.application.port.FilesystemObservations
import email.testinbox.application.port.FilesystemSnapshot
import email.testinbox.application.port.ObservedFilesystem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant

/**
 * Filesystem-containment contract §8: the evidence that reopens a full
 * filesystem. It needs an observation that began after the latest trip, is
 * fresh, and shows both bytes and inodes in reserve.
 */
class StorageFullEvidenceTest {
    private val gib = 1024L * 1024 * 1024
    private val t0 = Instant.parse("2026-10-08T12:00:00Z")
    private var dbNow = t0
    private var newest: ObservedFilesystem? = null
    private var reads = 0
    private var nanos = 0L

    private val observations =
        FilesystemObservations {
            reads++
            FilesystemSnapshot(dbNow, newest)
        }

    private fun gate(cache: Duration = Duration.ZERO) =
        StorageFullEvidence(
            observations,
            Duration.ofMinutes(15),
            3 * gib,
            inodeReserve = 1_000,
            negativeCacheFor = cache,
            nanoTime = { nanos },
        )

    private fun observed(
        startedAt: Instant = dbNow,
        age: Duration = Duration.ofMinutes(1),
        avail: Long = 10 * gib,
        inodes: Long = 1_000_000,
    ) = ObservedFilesystem(startedAt, age, avail, inodes)

    @Test
    fun `a fresh observation with bytes and inodes in reserve is evidence, at its exact boundaries`() {
        val g = gate()
        newest = observed(age = Duration.ZERO)
        g.evidence() shouldBe true
        newest = observed(age = Duration.ofMinutes(15), avail = 3 * gib, inodes = 1_000)
        g.evidence() shouldBe true
    }

    @Test
    fun `no observation, a stale one, one from the future, too little space or too few inodes is not evidence`() {
        val g = gate()
        newest = null
        g.evidence() shouldBe false
        newest = observed(age = Duration.ofMinutes(15).plusSeconds(1))
        g.evidence() shouldBe false
        newest = observed(age = Duration.ofSeconds(-1))
        g.evidence() shouldBe false
        newest = observed(avail = 3 * gib - 1)
        g.evidence() shouldBe false
        newest = observed(inodes = 999) // ENOSPC on inodes, with bytes to spare
        g.evidence() shouldBe false
    }

    @Test
    fun `an observation from before the trip never licenses a trial, and a failed trial needs a newer one`() {
        val g = gate()
        newest = observed(startedAt = t0.minusSeconds(60)) // looked fine a minute before the disk filled
        g.tripped()
        dbNow = t0.plusSeconds(1)
        g.evidence() shouldBe false // the 507 just contradicted it

        newest = observed(startedAt = t0.plusSeconds(30))
        dbNow = t0.plusSeconds(31)
        g.evidence() shouldBe true // a new observation, after the trip

        g.tripped() // the trial's real event hit ENOSPC again
        dbNow = t0.plusSeconds(40)
        g.evidence() shouldBe false // the same observation cannot license a second trial
        newest = observed(startedAt = t0.plusSeconds(50))
        g.evidence() shouldBe true
    }

    @Test
    fun `a negative answer is cached so a blocked node does not query on every DATA, and a trip clears the cache`() {
        val g = gate(cache = Duration.ofSeconds(5))
        newest = null
        g.evidence() shouldBe false
        g.evidence() shouldBe false
        reads shouldBe 1
        nanos += Duration.ofSeconds(6).toNanos()
        g.evidence() shouldBe false
        reads shouldBe 2
        g.tripped()
        g.evidence() shouldBe false
        reads shouldBe 3 // a trip forces a fresh read, to take its mark
    }

    @Test
    fun `A_obs, R_ops and the inode reserve must be positive`() {
        assertThrows<IllegalArgumentException> { StorageFullEvidence(observations, Duration.ZERO, 1, 1) }
        assertThrows<IllegalArgumentException> { StorageFullEvidence(observations, Duration.ofMinutes(1), 0, 1) }
        assertThrows<IllegalArgumentException> { StorageFullEvidence(observations, Duration.ofMinutes(1), 1, 0) }
    }

    @Test
    fun `the deployment's gate takes A_obs and R_ops from the declarations, and R_ops over B as the inode reserve`() {
        val fs =
            FilesystemDeclarations(
                blockSizeBytes = 4096,
                operationalReserveBytes = 4 * gib,
                capacityBytes = 48 * gib,
                observationMaxAge = Duration.ofMinutes(10),
            )
        val g = StorageFullEvidence.forDeclarations(observations, fs)
        newest = observed(age = Duration.ofMinutes(10), avail = 4 * gib, inodes = 4 * gib / 4096)
        g.evidence() shouldBe true
        newest = observed(age = Duration.ofMinutes(10).plusSeconds(1), avail = 4 * gib, inodes = 4 * gib / 4096)
        g.evidence() shouldBe false
        newest = observed(age = Duration.ofMinutes(1), avail = 4 * gib - 1, inodes = 4 * gib / 4096)
        g.evidence() shouldBe false
        newest = observed(age = Duration.ofMinutes(1), avail = 4 * gib, inodes = 4 * gib / 4096 - 1)
        g.evidence() shouldBe false
    }

    @Test
    fun `undeclared or under-declared figures fall back to the contract's floors - never to something easier`() {
        val none = FilesystemDeclarations()
        none.effectiveObservationMaxAge shouldBe Duration.ofMinutes(15)
        none.effectiveOperationalReserveBytes shouldBe 2 * gib
        FilesystemDeclarations(capacityBytes = 100 * gib).effectiveOperationalReserveBytes shouldBe 5 * gib
        FilesystemDeclarations(operationalReserveBytes = 7 * gib, capacityBytes = 100 * gib).effectiveOperationalReserveBytes shouldBe
            7 * gib
        FilesystemDeclarations(operationalReserveBytes = 1).effectiveOperationalReserveBytes shouldBe 2 * gib
    }
}
