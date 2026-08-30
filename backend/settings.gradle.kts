rootProject.name = "testinbox-backend"

include(
    "domain",
    "application",
    "persistence",
    "storage",
    "notification",
    "api",
    "ingestion",
    "architecture",
    "e2e",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// The JVM SDK is an independent build (ADR-013/023); included here so the
// e2e module can exercise the real published-artifact coordinates.
includeBuild("../sdk/kotlin")
