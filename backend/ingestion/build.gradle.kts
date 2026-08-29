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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.subethasmtp)
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.jsoup)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.awaitility)
    testImplementation(libs.aws.s3)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
