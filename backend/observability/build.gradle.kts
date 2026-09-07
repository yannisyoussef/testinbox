plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("io.micrometer:micrometer-core")

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
    // The exported Prometheus names are what Ops actually scrapes, and
    // Micrometer's naming convention rewrites them (unit suffixes, `_total`).
    // Asserting the meter id would test the wrong string.
    testImplementation("io.micrometer:micrometer-registry-prometheus")
}
