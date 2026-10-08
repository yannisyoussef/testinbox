plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":persistence"))
    testImplementation(project(":storage"))
    testImplementation(project(":notification"))
    testImplementation(project(":observability"))
    testImplementation(project(":api"))
    testImplementation(project(":ingestion"))
    testImplementation(project(":migrator"))
    // The ADR-035 §11 benchmark CLI assembles the real protocol by hand; it is
    // scanned so its one exemption is named in the rules, not silent.
    testImplementation(project(":benchmark"))
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
    // Only for the fixture that proves the unfenced-write rule can fail.
    testImplementation(libs.aws.s3)
}
