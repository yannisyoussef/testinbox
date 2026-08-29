plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(project(":api"))
    testImplementation(project(":ingestion"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.karate.junit5)
    testImplementation(libs.awaitility)
    testImplementation(libs.jakarta.mail.api)
    testImplementation(libs.angus.mail)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    // Black-box acceptance: run serially, real containers.
    maxParallelForks = 1
}
