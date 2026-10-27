package com.demonlab.lune.tools

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Breaks "crash on every launch" loops caused by a native (C/C++) abort while a track is being loaded.
 *
 * A native abort cannot be caught with try/catch, and Transient reopens the last song when it starts, so a file that
 * kills the process would kill it again on every launch. This guard remembers which song was being loaded:
 *
 *  1. [begin]  - written to disk (synchronously) just before ExoPlayer starts reading a file.
 *  2. [end]    - written when the track is ready, failed cleanly, or was released.
 *  3. [resolvePrevious] - on the next start (Application.onCreate): if a "loading" marker is still there AND the system
 *     says the last process died of a native crash, that song is quarantined.
 *  4. [isQuarantined]   - MusicService then plays that song with the standard player (MediaPlayer) instead of ExoPlayer.
 *
 * The quarantine is tied to the app's versionCode, so installing a new build gives the song a fresh chance.
 */
object NativeCrashGuard {
    private const val PREFS = "native_crash_guard"
    private const val KEY_LOADING_ID = "loading_song_id"
    private const val KEY_LOADING_AT = "loading_started_at"
    private const val KEY_BAD_IDS = "quarantined_ids"
    private const val KEY_BAD_VERSION = "quarantine_version"

    @Volatile private var cachedVersion = Long.MIN_VALUE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Call right before ExoPlayer starts reading [songId]. commit() = synchronous, the process may abort a moment later. */
    fun begin(context: Context, songId: Long) {
        runCatching {
            prefs(context).edit()
                .putLong(KEY_LOADING_ID, songId)
                .putLong(KEY_LOADING_AT, System.currentTimeMillis())
                .commit()
        }
    }

    /** The load finished (ready / error / released). Only clears the marker if it still belongs to [songId]. */
    fun end(context: Context, songId: Long) {
        runCatching {
            val p = prefs(context)
            if (p.getLong(KEY_LOADING_ID, -1L) == songId) {
                p.edit().remove(KEY_LOADING_ID).remove(KEY_LOADING_AT).commit()
            }
        }
    }

    /** Call once from Application.onCreate (after DiagnosticLogger.install), before any playback is restored. */
    fun resolvePrevious(context: Context) {
        runCatching {
            val p = prefs(context)
            val songId = p.getLong(KEY_LOADING_ID, -1L)
            if (songId == -1L) return
            val startedAt = p.getLong(KEY_LOADING_AT, 0L)
            p.edit().remove(KEY_LOADING_ID).remove(KEY_LOADING_AT).commit() // consume the marker either way
            if (!diedNatively(context, startedAt)) return

            val version = versionCode(context)
            val ids = if (p.getLong(KEY_BAD_VERSION, -1L) == version) {
                p.getStringSet(KEY_BAD_IDS, null).orEmpty().toMutableSet()
            } else {
                mutableSetOf()
            }
            ids.add(songId.toString())
            p.edit().putLong(KEY_BAD_VERSION, version).putStringSet(KEY_BAD_IDS, ids).commit()
            DiagnosticLogger.error(
                "Playback",
                "The app crashed natively while loading song id $songId; it will use the standard player until the next app update"
            )
        }
    }

    /** True when [songId] must not go through ExoPlayer: the previous process died natively while loading it (same app version). */
    fun isQuarantined(context: Context, songId: Long): Boolean {
        val p = prefs(context)
        if (p.getLong(KEY_BAD_VERSION, -1L) != versionCode(context)) return false
        return p.getStringSet(KEY_BAD_IDS, null)?.contains(songId.toString()) == true
    }

    private fun diedNatively(context: Context, since: Long): Boolean {
        // Before Android 11 the system cannot tell us why the process ended. A leftover marker is then the best clue.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5).any {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE && it.timestamp >= since
            }
        }.getOrDefault(true)
    }

    @Suppress("DEPRECATION")
    private fun versionCode(context: Context): Long {
        if (cachedVersion != Long.MIN_VALUE) return cachedVersion
        val code = runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode else pkg.versionCode.toLong()
        }.getOrDefault(0L)
        cachedVersion = code
        return code
    }
}
