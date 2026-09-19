plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(project(":persistence"))
    implementation(project(":storage"))
    implementation(project(":notification"))
    implementation(project(":observability"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Scrape endpoint on the private management port (docs/architecture/observability.md).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.awaitility)
    testImplementation("io.swagger.parser.v3:swagger-parser:2.1.48")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The Boot BOM manages Tomcat 11.0.24 (CVE-2026-65182, CRITICAL, fixed in
    // 11.0.25). A plain `platform()` import lets a higher direct constraint win,
    // so all three embed artifacts move together; remove once the Boot BOM
    // itself manages >= 11.0.25.
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:${libs.versions.tomcatEmbed.get()}")
        implementation("org.apache.tomcat.embed:tomcat-embed-el:${libs.versions.tomcatEmbed.get()}")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:${libs.versions.tomcatEmbed.get()}")
    }
}

// /actuator/info must be able to answer "what exact commit is running?" (ADR-028).
springBoot {
    buildInfo()
}
