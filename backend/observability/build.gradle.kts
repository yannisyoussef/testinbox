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
}
