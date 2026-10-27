# ==============================================================================
# LUNE - PROGUARD / R8 RULES
# ==============================================================================

# Keep line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep generic attributes
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ==============================================================================
# ROOM DATABASE
# ==============================================================================
-keep class com.demonlab.lune.data.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}

# ==============================================================================
# GSON & DATA CLASSES (Cache & Backup)
# ==============================================================================
-keep class com.demonlab.lune.tools.Song { *; }
-keep class com.demonlab.lune.tools.PlaylistExportData { *; }
-keep class com.demonlab.lune.tools.PlaylistData { *; }
-keep class com.demonlab.lune.tools.SongMetadata { *; }

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ==============================================================================
# TOOLS (PlaybackManager, MusicService, SettingsManager, audio pipeline)
# ==============================================================================
-keep class com.demonlab.lune.tools.** { *; }

# ==============================================================================
# AUDIO EFFECTS (reflection in DynamicsEffect)
# ==============================================================================
-keep class com.demonlab.lune.audio.** { *; }

# ==============================================================================
# UI ACTIVITIES (EqualizerActivity, etc. — Compose state)
# ==============================================================================
-keep class com.demonlab.lune.ui.** { *; }

# ==============================================================================
# JAUDIOTAGGER (Metadata extraction)
# ==============================================================================
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# ==============================================================================
# KOTLIN COROUTINES
# ==============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ==============================================================================
# OKIO / OKHTTP (transitive deps via Coil)
# ==============================================================================
-dontwarn okio.**
-dontwarn okhttp3.**

# ==============================================================================
# AI ENGINE & MODELS (Telemetry & Serialization)
# ==============================================================================
-keep class com.demonlab.lune.ai.** { *; }
-keepclassmembers class com.demonlab.lune.ai.** { *; }


# ==============================================================================
# BIT-PERFECT USB AUDIO (decent-player libs: JNI + reflection-loaded decoders)
# ==============================================================================
-keep class com.decent.usbaudio.** { *; }
-keep class androidx.media3.decoder.flac.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class org.jellyfin.media3.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# SftpDataSource ships in the wrapper but Lune never uses it (no INTERNET permission)
-dontwarn com.jcraft.jsch.**
-dontwarn com.github.mwiede.**
# >>> transient-jni-keep >>>
# Why: libflacJNI.so (flac_jni.cc) calls FindClass() for FlacStreamMetadata and, for FLACs with embedded
# cover art, PictureFrame. Both live in media3-extractor, outside the keep rules for decoder.flac.**.
# R8 cannot see that, so it shrinks/renames the class -> FindClass returns null -> GetMethodID aborts
# the whole process (SIGABRT, "JNI DETECTED ERROR IN APPLICATION: java_class == null").
-keep class androidx.media3.extractor.FlacStreamMetadata { *; }
-keep class androidx.media3.extractor.metadata.flac.PictureFrame { *; }
-keep class androidx.media3.decoder.flac.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
# Keep names AND the classes used in native-method signatures (parameter / return types).
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
# Auto-detected from FindClass(...) in this repo's native sources:
-keep class androidx.media3.extractor.FlacStreamMetadata { *; }
-keep class androidx.media3.extractor.metadata.flac.PictureFrame { *; }
# <<< transient-jni-keep <<<
