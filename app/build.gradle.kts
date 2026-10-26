import java.io.FileInputStream
import java.util.Properties


val keystorePropertiesFile = layout.projectDirectory.file("../keystore.properties").asFile
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.demonlab.lune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.transientplayer.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // APK size: only these CPU types are built and packaged. arm64-v8a runs on every phone made since about 2019
        // and keeps the APK small; the native audio libraries (FFmpeg, FLAC, USB driver) were being included four times.
        // Need more? Build with  -Ptransient.abis=arm64-v8a,armeabi-v7a,x86_64
        ndk {
            abiFilters += (project.findProperty("transient.abis") as String? ?: "arm64-v8a").split(",").map { it.trim() }
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes.add("**/baseline.prof")
            excludes.add("**/baseline.profm")
        }
        jniLibs {
            keepDebugSymbols.add("**/libandroidx.graphics.path.so")
        }
    }
}

base {
    archivesName = "Transient"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    // ── Bit-perfect USB DAC output (decent-player, MIT) ──
    implementation(project(":decent-usb-audio-driver"))
    implementation(project(":decent-usb-audio-wrapper-media3"))
    implementation(project(":decent-media3-decoder-flac"))
    implementation(libs.androidx.media3.exoplayer)
    // FFmpeg decodes every non-FLAC format (and 24-bit sources) without truncating to 16-bit.
    implementation(libs.jellyfin.ffmpeg.decoder)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation.graphics)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.gson)
    implementation(libs.jaudiotagger)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        val capitalizedName = variantName.replaceFirstChar { it.uppercase() }

        tasks.matching {
            it.name == "compile${capitalizedName}ArtProfile" ||
                    it.name == "merge${capitalizedName}ArtProfile"
        }.configureEach {
            enabled = false
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
