package email.testinbox.architecture.fixtures

import email.testinbox.domain.storage.InboxShare
import email.testinbox.domain.storage.StorageCapacityPolicy

/**
 * NEVER production code. A second derivation of the ADR-035 storage policy,
 * kept only so the architecture test can prove its rule detects one.
 */
class RoguePolicyFixture {
    fun policy(): StorageCapacityPolicy = StorageCapacityPolicy(1_000, InboxShare.of("0.5"), 1L shl 40, 0)
}
