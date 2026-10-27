package com.demonlab.lune

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.demonlab.lune.tools.AudioThumbnailFetcher
import com.demonlab.lune.tools.DiagnosticLogger
import com.demonlab.lune.tools.NativeCrashGuard

class LuneApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.install(this)
        // If the last run died natively while loading a song, remember which one so it is not reopened the same way.
        NativeCrashGuard.resolvePrevious(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) DiagnosticLogger.onLowMemory(level)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(AudioThumbnailFetcher.Factory(this@LuneApplication))
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
