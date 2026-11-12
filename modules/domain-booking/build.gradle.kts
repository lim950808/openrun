plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

dependencies {
    api(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    api(libs.boot.starter.jdbc)
    api(libs.boot.starter.data.redis)
    api(libs.bucket4j.core)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.shedlock.spring)
    implementation(libs.micrometer.core)
    implementation(project(":modules:support"))
    implementation(project(":modules:outbox"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
