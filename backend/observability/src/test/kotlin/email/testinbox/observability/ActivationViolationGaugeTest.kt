package email.testinbox.observability

import email.testinbox.domain.storage.StorageCapacityPolicy
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * TI-STORAGE-006b P1: the fail-closed rule's observable is
 * `testinbox_storage_activation_violation` = 1. The barrier tests prove the
 * port is called; this proves the real gauge reads 1 and back to 0.
 */
class ActivationViolationGaugeTest {
    @Test
    fun `the activation violation gauge reads 1 while set and 0 once cleared`() {
        val registry = SimpleMeterRegistry()
        val protocol = MicrometerStorageProtocolMetrics(registry, StorageCapacityPolicy.ADR_035_REFERENCE)

        fun gauge() = registry.get(MicrometerStorageProtocolMetrics.ACTIVATION_VIOLATION).gauge().value()
        gauge() shouldBe 0.0
        protocol.activationViolation(true)
        gauge() shouldBe 1.0
        protocol.activationViolation(false)
        gauge() shouldBe 0.0
    }
}
