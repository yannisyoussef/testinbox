plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.springframework:spring-context")
    implementation(libs.aws.s3)
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.junit)
    // Log capture for the URL-redaction proof (ADR-035 §18 gate 3).
    testImplementation("ch.qos.logback:logback-classic")
}
