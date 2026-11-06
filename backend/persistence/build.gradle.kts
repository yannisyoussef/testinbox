plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.jackson3.bom))
    implementation(platform(libs.jackson2.bom))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework:spring-context")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // Jackson 3. `JdbcIdempotencyRecords` owns a `tools.jackson` ObjectMapper
    // for the snapshot column; declared rather than inherited transitively
    // from the JDBC starter, so a Boot upgrade that stops exporting it fails
    // here with a missing dependency instead of an unexplained missing class.
    implementation("tools.jackson.core:jackson-databind")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.spring.boot.dependencies))

    testImplementation(platform(libs.jackson3.bom))

    testImplementation(platform(libs.jackson2.bom))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.postgresql:postgresql")
    testImplementation("org.flywaydb:flyway-core")
    testImplementation(libs.awaitility)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The ADR-035 T1 sanity benchmark (docs/adr/0035-benchmark/t1-admission) is
// not a test: it measures, and asserts only what a pathology would break.
// It runs only on request, and never counts toward the suite's floor.
tasks.test {
    // The slot-lane escrow PROTOTYPE's schema (ADR-038 PROPOSED, M1): benchmark-only, never in
    // db/migration. The prototype tests apply it to their own throwaway databases after Flyway.
    val escrowProto = rootProject.file("benchmark/src/main/resources/escrow-proto/v28-proto-slot-lane-escrow.sql")
    inputs.file(escrowProto)
    systemProperty("testinbox.escrowProtoSql", escrowProto.absolutePath)
    if (!project.hasProperty("storageBenchmark")) {
        exclude("**/StorageAdmissionBenchmark*")
    }
}
