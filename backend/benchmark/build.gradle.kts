plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The ADR-035 §11 global-admission benchmark harness (TI-STORAGE-006).
//
// It is a deployable measurement tool, not a test suite and not a service.
// It drives the REAL protocol objects (`StorageAdmission` over
// `JdbcStorageAdmission`, `GuardedStorage` with the real JDBC fence, slots,
// breaker and transaction runner, `ExpireInboxes` for retention) against a
// benchmark database it has proven empty or created itself, and writes a
// machine-readable verdict. See docs/dev/storage-benchmark.md.
dependencies {
    implementation(project(":application"))
    implementation(project(":persistence"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.postgresql:postgresql")
    // `--local` smoke mode starts a throwaway postgres:16-alpine. Same
    // Testcontainers line the persistence suite uses; nothing new.
    implementation(libs.testcontainers.postgresql)

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("email.testinbox.benchmark.MainKt")
    applicationName = "storage-benchmark"
}
