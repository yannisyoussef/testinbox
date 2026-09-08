plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("io.micrometer:micrometer-core")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
    // The exported Prometheus names are what Ops actually scrapes, and
    // Micrometer's naming convention rewrites them (unit suffixes, `_total`).
    // Asserting the meter id would test the wrong string.
    testImplementation("io.micrometer:micrometer-registry-prometheus")
    // The audit-log adapter is asserted by capturing what it actually emits;
    // a test that stubbed the logger would prove nothing about leakage.
    testImplementation("ch.qos.logback:logback-classic")
}
