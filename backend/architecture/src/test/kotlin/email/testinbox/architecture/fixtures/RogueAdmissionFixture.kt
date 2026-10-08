package email.testinbox.architecture.fixtures

import email.testinbox.application.port.StorageAdmissionStore
import email.testinbox.application.usecase.StorageAdmission
import email.testinbox.domain.storage.StorageCapacityPolicy
import email.testinbox.domain.storage.StorageEnforcement

/**
 * NEVER production code. A second T1 construction site passing a literal
 * enforcement value, kept only so the architecture test can prove its rule
 * detects one (TI-STORAGE-006).
 */
class RogueAdmissionFixture {
    fun admission(store: StorageAdmissionStore): StorageAdmission =
        StorageAdmission(store, StorageCapacityPolicy.ADR_035_REFERENCE, StorageEnforcement.ALL)
}
