pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "Joseph Clipboard Client"
include(":app")

// Desktop clients. The portable ~94% of the desktop code lives in :desktop-core; each platform
// module supplies only its own OS backend (PlatformServices) plus its packaging. Keeping the
// Windows module at its original "Window Ver" path is deliberate — OffBridge.iss, the build2
// output dir and the release ritual all key off it.
include(":desktop-core")
project(":desktop-core").projectDir = file("Desktop Core")
include(":window-ver")
project(":window-ver").projectDir = file("Window Ver")
include(":linux-ver")
project(":linux-ver").projectDir = file("Linux Ver")
