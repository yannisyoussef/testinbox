plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework:spring-context")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(libs.spring.boot.dependencies))
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
