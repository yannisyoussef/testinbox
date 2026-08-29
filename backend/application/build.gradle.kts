plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":domain"))
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
}
