rootProject.name = "openrun"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "modules:support",
    "modules:schema",
    "modules:domain-catalog",
    "modules:domain-booking",
    "modules:domain-payment",
    "modules:outbox",
    "apps:api",
)
