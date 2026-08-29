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
