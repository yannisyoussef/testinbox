plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
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

    // Static analysis (docs/quality/strategy.md): a failing gate, not advisory.
    // Detached from `check` on purpose: `build` runs on Java 25, which the
    // Detekt 1.23.x analyzer cannot run on. The dedicated static-analysis CI
    // job runs `./gradlew detekt` on Java 21.
    apply(plugin = "io.gitlab.arturbosch.detekt")
    tasks.matching { it.name == "check" }.configureEach {
        setDependsOn(dependsOn.filterNot { it.toString().contains("detekt", ignoreCase = true) })
    }
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("../config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
        val analyzerJdk =
            extensions
                .getByType<JavaToolchainService>()
                .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .map { it.metadata.installationPath }
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            // Detekt 1.23.x (current stable) cannot run on a Java 25 runtime and caps
            // jvmTarget at 22, so the analyzer is pinned to Java 21 and the static
            // analysis job runs Gradle on it. This bounds the analyzer only — the
            // production code still compiles against the Java 25 toolchain.
            jdkHome.set(analyzerJdk)
            jvmTarget = "22"
            reports {
                html.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
                txt.required.set(false)
            }
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
