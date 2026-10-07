package email.testinbox.application.storage

import email.testinbox.application.LimitsConfig
import email.testinbox.application.LimitsProperties
import email.testinbox.domain.storage.StorageCapacityPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * ADR-035 §3 through one factory (TI-STORAGE-004 §6): the ceilings T1 admits
 * against and the ones the API shows as `limitBytes` are the same object
 * shape, derived the same way, from the same configuration. The wirings are
 * held to this factory by `DependencyRuleTest` (no other main-code class
 * constructs a `StorageCapacityPolicy`).
 */
class EffectiveStoragePolicyTest {
    private fun limits(maxStoredBytes: Long): LimitsConfig = LimitsProperties(maxStoredBytes = maxStoredBytes).toConfig()

    private fun StorageCapacityPolicy.shape() =
        listOf(workspaceLimitBytes, inboxShare.value, inboxLimitBytes, globalLimitBytes, finalizeBudgetBytes)

    @Test
    fun `the default configuration yields the ADR-035 planning values`() {
        val policy = EffectiveStoragePolicy.of(LimitsProperties().toConfig())
        policy.workspaceLimitBytes shouldBe 2L * 1024 * 1024 * 1024
        policy.inboxLimitBytes shouldBe 512L * 1024 * 1024
        policy.shape() shouldBe StorageCapacityPolicy.ADR_035_REFERENCE.shape()
    }

    @Test
    fun `the workspace limit is the configured max-stored-bytes, and the inbox limit follows the share`() {
        val policy = EffectiveStoragePolicy.of(limits(1_000_000))
        policy.workspaceLimitBytes shouldBe 1_000_000
        policy.inboxLimitBytes shouldBe 250_000
    }

    @Test
    fun `two derivations from the same configuration are identical, so T1 and the API cannot disagree`() {
        val config = limits(123_456_789)
        EffectiveStoragePolicy.of(config).shape() shouldBe EffectiveStoragePolicy.of(config).shape()
    }

    @Test
    fun `a workspace limit too small for the share to floor above zero observes against the whole workspace`() {
        // Only ever a test setting; the policy's own invariant forbids a zero inbox limit.
        val policy = EffectiveStoragePolicy.of(limits(3))
        policy.workspaceLimitBytes shouldBe 3
        policy.inboxLimitBytes shouldBe 3
    }

    @Test
    fun `the workspace limit never exceeds G, and G and H stay the reference values`() {
        val policy = EffectiveStoragePolicy.of(limits(Long.MAX_VALUE))
        policy.workspaceLimitBytes shouldBe StorageCapacityPolicy.ADR_035_REFERENCE.globalLimitBytes
        policy.globalLimitBytes shouldBe StorageCapacityPolicy.ADR_035_REFERENCE.globalLimitBytes
        policy.finalizeBudgetBytes shouldBe StorageCapacityPolicy.ADR_035_REFERENCE.finalizeBudgetBytes
    }
}
