plugins {
    id("com.android.library")
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        externalNativeBuild { cmake { cppFlags("") } }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin { jvmToolchain(21) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
}

// ── Lune integration ─────────────────────────────────────────────────────────
// This module's CMake reuses the libFLAC checkout that lives in the FLAC module.
tasks.matching {
    it.name == "preBuild" || it.name.startsWith("generateJsonModel") || it.name.startsWith("configureCMake")
}.configureEach { dependsOn(":decent-media3-decoder-flac:fetchLibflac") }
