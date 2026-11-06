package email.testinbox.ingestion.config

// Fixture: the property class with the ADR-035 §14 phase 2 default, and T1's mode a lock protocol.
data class StorageProperties(
    val nodeId: String = "testinbox-ingestion",
    val enforcement: StorageEnforcement = StorageEnforcement.OFF,
    val admissionLocking: AdmissionLocking = AdmissionLocking.GLOBAL,
)
