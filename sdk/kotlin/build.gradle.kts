plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    `java-library`
}

group = "email.testinbox"
version = "0.1.0-SNAPSHOT"

// ADR-023: the public SDK artifact targets Java 17 bytecode and APIs,
// regardless of the backend's Java 25 runtime. -Xjdk-release enforces the
// API baseline mechanically, not by convention.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
        // Conservative language level so consumers on older Kotlin toolchains can depend on us.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Same static-analysis gate as the backend (docs/quality/strategy.md). The
// Detekt 1.23.x analyzer cannot run on Java 25, so it is pinned to a Java 21
// launcher and detached from `check`; the artifact still targets Java 17
// bytecode and `build` still runs on the Java 25 toolchain.
detekt {
    config.setFrom(rootProject.file("../../config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

tasks.matching { it.name == "check" }.configureEach {
    setDependsOn(dependsOn.filterNot { it.toString().contains("detekt", ignoreCase = true) })
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jdkHome.set(
        javaToolchains
            .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .map { it.metadata.installationPath },
    )
    jvmTarget = "17"
    reports {
        html.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
        txt.required.set(false)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
