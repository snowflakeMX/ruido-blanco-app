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

rootProject.name = "ruido-blanco-app"

// Pure Kotlin logic (detection, states, noise). Builds and tests without the Android SDK.
includeBuild("core")
include(":app")
