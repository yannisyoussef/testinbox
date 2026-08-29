plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint()
    }
}

subprojects {
    group = "email.testinbox"
    version = "0.1.0-SNAPSHOT"

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed", "skipped")
                showStandardStreams = false
            }
        }
    }
}
