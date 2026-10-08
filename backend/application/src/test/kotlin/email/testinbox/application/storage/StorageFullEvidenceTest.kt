package email.testinbox.application.storage

import email.testinbox.application.port.FilesystemObservations
import email.testinbox.application.port.ObservedFilesystem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** Filesystem-containment contract §8: only a fresh observation with R_ops available is evidence. */
class StorageFullEvidenceTest {
    private val gib = 1024L * 1024 * 1024

    private fun evidence(observed: ObservedFilesystem?) =
        StorageFullEvidence(FilesystemObservations { observed }, Duration.ofMinutes(15), 3 * gib)()

    @Test
    fun `a fresh observation with the reserve available is evidence`() {
        evidence(ObservedFilesystem(Duration.ofMinutes(1), 3 * gib)) shouldBe true
        evidence(ObservedFilesystem(Duration.ofMinutes(15), 10 * gib)) shouldBe true // the boundary counts
    }

    @Test
    fun `no observation, a stale one, one from the future, or too little space is not evidence`() {
        evidence(null) shouldBe false
        evidence(ObservedFilesystem(Duration.ofMinutes(15).plusSeconds(1), 10 * gib)) shouldBe false
        evidence(ObservedFilesystem(Duration.ofSeconds(-1), 10 * gib)) shouldBe false
        evidence(ObservedFilesystem(Duration.ofMinutes(1), 3 * gib - 1)) shouldBe false
    }

    @Test
    fun `A_obs and R_ops must be positive`() {
        assertThrows<IllegalArgumentException> { StorageFullEvidence({ null }, Duration.ZERO, 1) }
        assertThrows<IllegalArgumentException> { StorageFullEvidence({ null }, Duration.ofMinutes(1), 0) }
    }

    @Test
    fun `undeclared figures fall back to the contract's floors - never to something easier`() {
        val none = FilesystemDeclarations()
        none.effectiveObservationMaxAge shouldBe Duration.ofMinutes(15)
        none.effectiveOperationalReserveBytes shouldBe 2 * gib
        FilesystemDeclarations(capacityBytes = 100 * gib).effectiveOperationalReserveBytes shouldBe 5 * gib
        FilesystemDeclarations(operationalReserveBytes = 7 * gib, capacityBytes = 100 * gib).effectiveOperationalReserveBytes shouldBe
            7 * gib
    }
}
