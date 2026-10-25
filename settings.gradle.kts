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
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Transient"
include(":app")

// ── Bit-perfect USB audio (decent-player libs, MIT) ──────────────────────────
// The three modules MUST stay siblings inside ./decent : the driver's CMake file
// reaches into the FLAC module for the shared libFLAC checkout.
include(":decent-usb-audio-driver")
include(":decent-usb-audio-wrapper-media3")
include(":decent-media3-decoder-flac")
project(":decent-usb-audio-driver").projectDir = file("decent/decent-usb-audio-driver")
project(":decent-usb-audio-wrapper-media3").projectDir = file("decent/decent-usb-audio-wrapper-media3")
project(":decent-media3-decoder-flac").projectDir = file("decent/decent-media3-decoder-flac")
