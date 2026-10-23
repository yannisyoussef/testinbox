package email.testinbox.api.config

// Fixture: the property class with the ADR-035 §14 phase 2 default.
data class StorageProperties(
    val nodeId: String = "testinbox-api",
    // enforcement: StorageEnforcement = StorageEnforcement.ALL  <- a comment, not the default
    val enforcement: StorageEnforcement = StorageEnforcement.OFF,
)
