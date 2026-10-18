pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QuickShare"

// :core holds all platform-independent logic (wire protocol, discovery, transfer engine).
// It is a plain JVM module so it can be unit-tested without the Android SDK.
include(":app")
include(":core")
