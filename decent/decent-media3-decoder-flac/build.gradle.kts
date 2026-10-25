plugins {
    id("com.android.library")
}

android {
    namespace = "com.decent.media3.decoder.flac"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        externalNativeBuild {
            cmake {
                arguments += listOf("-DWITH_OGG=OFF", "-DINSTALL_MANPAGES=OFF")
                targets += "flacJNI"
            }
        }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.21.0+"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.media3:media3-decoder:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("androidx.media3:media3-common:1.9.3")
    implementation("androidx.media3:media3-extractor:1.9.3")
    compileOnly("org.checkerframework:checker-qual:3.42.0")
    compileOnly("com.google.errorprone:error_prone_annotations:2.28.0")
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("com.google.guava:guava:33.0.0-android")
}

// ── Lune integration ─────────────────────────────────────────────────────────
// The xiph/flac sources are not vendored (see setup.sh). Both this module and
// :decent-usb-audio-driver compile them, so fetch once, before any native
// configure step, whenever the checkout is missing. Needs `git` + network.
val libflacDir = layout.projectDirectory.dir("src/main/jni/libflac")
val fetchLibflac = tasks.register<Exec>("fetchLibflac") {
    onlyIf { !libflacDir.file("CMakeLists.txt").asFile.exists() }
    commandLine("git", "clone", "--depth=1", "https://github.com/xiph/flac.git", libflacDir.asFile.absolutePath)
}
tasks.matching {
    it.name == "preBuild" || it.name.startsWith("generateJsonModel") || it.name.startsWith("configureCMake")
}.configureEach { dependsOn(fetchLibflac) }
