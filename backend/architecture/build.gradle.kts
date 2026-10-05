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
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.archunit.junit5)
    // Only for the fixture that proves the unfenced-write rule can fail.
    testImplementation(libs.aws.s3)
}
