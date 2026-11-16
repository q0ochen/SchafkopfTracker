// Nur freie, von F-Droid akzeptierte Quellen. Kein foojay-Toolchain-Plugin (F-Droids Scanner lehnt es ab).
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

rootProject.name = "SchafkopfTracker"

include(":domain", ":data", ":database", ":app")
