plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    api(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    api(libs.boot.starter.data.jpa)
    implementation(project(":modules:support"))
    runtimeOnly(project(":modules:schema"))
}

// Open entity classes so Hibernate can create lazy proxies (kotlin-jpa only adds no-arg constructors).
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
