plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    implementation(platform(libs.spring.boot.dependencies))
    implementation("io.micrometer:micrometer-core")
}
