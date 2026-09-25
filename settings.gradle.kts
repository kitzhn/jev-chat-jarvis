pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jev-android"
include(":app")

// CI-only messenger fixtures intentionally stay out of normal IDE/Gradle sync.
val integrationFixturesEnabled =
    System.getenv("JEV_INTEGRATION_FIXTURES") == "1" ||
        gradle.startParameter.projectProperties["jevIntegrationFixtures"] == "true"
if (integrationFixturesEnabled) {
    include(":integration-fixture")
}
