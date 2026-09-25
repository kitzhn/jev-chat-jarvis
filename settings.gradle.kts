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
// Security validation PR marker; no runtime behavior change.
if (System.getenv("JEV_INTEGRATION_FIXTURES") == "1") {
    include(":integration-fixture")
}
