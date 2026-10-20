plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.jackson3.bom))
    implementation(platform(libs.jackson2.bom))
    implementation("org.postgresql:postgresql")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform(libs.spring.boot.dependencies))

    testImplementation(platform(libs.jackson3.bom))

    testImplementation(platform(libs.jackson2.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.awaitility)
    testImplementation("ch.qos.logback:logback-classic")
}
