plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.jackson3.bom))
    testImplementation(platform(libs.jackson2.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
}
