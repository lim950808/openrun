plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    api(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    api(libs.boot.starter.jdbc)
    // RestClient for TossPgClient (no web server: spring-web only).
    api(libs.spring.web)
    implementation(libs.jackson.databind)
    implementation(libs.shedlock.spring)
    implementation(libs.micrometer.core)
    implementation(project(":modules:support"))
    implementation(project(":modules:outbox"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
