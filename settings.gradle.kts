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

// REVIEW(MAJOR-04): CI-only messenger fixture is currently part of normal project sync.
 // See docs/CODE_REVIEW_2026-09-25.md before making inclusion conditional.
include(":integration-fixture")
