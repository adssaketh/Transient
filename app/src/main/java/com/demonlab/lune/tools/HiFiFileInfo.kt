package com.demonlab.lune.tools

import android.content.Context
import java.io.File
import org.jaudiotagger.tag.FieldKey
import org.json.JSONObject

/** What the library scan needs from a hi-fi file: its format facts and its text tags. */
class HiFiInfo(
    val sampleRate: Int,
    val bitsPerSample: Int,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val track: String?,
    val composer: String?
)

/**
 * Reads [HiFiInfo] for the library scan, and remembers it on disk per file (path + size + modified time).
 *
 * Before this, every scan re-read the tags of every hi-fi file; for WAVs with embedded cover art that meant loading tens
 * of MB per file into memory on every start. Now a file is read once, and again only if it changes. WAVs use the light
 * reader (no picture loaded); everything else, and any WAV the light reader cannot handle, goes through jaudiotagger one
 * file at a time.
 */
object HiFiFileInfo {
    private const val FILE_NAME = "hifi_tag_cache_v1.json"
    private val lock = Any()
    private var cache: HashMap<String, HiFiInfo>? = null
    private val seen = HashSet<String>()
    private var dirty = false
    private var hits = 0
    private var misses = 0
    private var scanStartMs = 0L

    private fun loadLocked(context: Context): HashMap<String, HiFiInfo> {
        cache?.let { return it }
        val map = HashMap<String, HiFiInfo>()
        try {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val o = root.getJSONObject(key)
                    map[key] = HiFiInfo(
                        o.optInt("sr"), o.optInt("bd"),
                        o.optString("ti").ifEmpty { null }, o.optString("ar").ifEmpty { null },
                        o.optString("aa").ifEmpty { null }, o.optString("al").ifEmpty { null },
                        o.optString("ge").ifEmpty { null }, o.optString("tr").ifEmpty { null },
                        o.optString("co").ifEmpty { null }
                    )
                }
            }
        } catch (_: Throwable) {
            map.clear() // unreadable cache: start again, nothing is lost but time
        }
        cache = map
        return map
    }

    /** Call at the start of a full scan. */
    fun beginScan() {
        synchronized(lock) {
            seen.clear()
            hits = 0
            misses = 0
            scanStartMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    fun get(context: Context, file: File, extension: String): HiFiInfo {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        synchronized(lock) {
            seen.add(key)
            loadLocked(context)[key]?.let { hits++; return it }
            misses++
        }
        val info = read(file, extension)
        synchronized(lock) {
            loadLocked(context)[key] = info
            dirty = true
        }
        return info
    }

    /** Call at the end of a full scan: saves new entries and forgets files that are gone. */
    fun flush(context: Context) {
        synchronized(lock) {
            val rt = Runtime.getRuntime()
            DiagnosticLogger.note(
                "SCAN tags: $hits from the saved cache, $misses read from files, " +
                    "${android.os.SystemClock.elapsedRealtime() - scanStartMs} ms | heap " +
                    "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB"
            )
            val map = loadLocked(context)
            if (map.keys.retainAll(seen)) dirty = true
            if (!dirty) return
            try {
                val root = JSONObject()
                for ((key, i) in map) {
                    val o = JSONObject()
                    o.put("sr", i.sampleRate); o.put("bd", i.bitsPerSample)
                    i.title?.let { o.put("ti", it) }; i.artist?.let { o.put("ar", it) }
                    i.albumArtist?.let { o.put("aa", it) }; i.album?.let { o.put("al", it) }
                    i.genre?.let { o.put("ge", it) }; i.track?.let { o.put("tr", it) }
                    i.composer?.let { o.put("co", it) }
                    root.put(key, o)
                }
                val dir = context.applicationContext.filesDir
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(File(dir, FILE_NAME))) tmp.delete()
                dirty = false
            } catch (_: Throwable) {
                // not saved this time; the next scan simply reads the files again
            }
        }
    }

    private fun read(file: File, extension: String): HiFiInfo {
        if (extension == "wav") WavTags.read(file)?.let { return it }
        val audioFile = TagReader.read(file)
        val header = audioFile.audioHeader
        val tag = audioFile.tag
        fun field(key: FieldKey): String? =
            tag?.let { runCatching { it.getFirst(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        return HiFiInfo(
            header.sampleRateAsNumber, header.bitsPerSample,
            field(FieldKey.TITLE), field(FieldKey.ARTIST) ?: field(FieldKey.ALBUM_ARTIST),
            field(FieldKey.ALBUM_ARTIST), field(FieldKey.ALBUM), field(FieldKey.GENRE),
            field(FieldKey.TRACK), field(FieldKey.COMPOSER)
        )
    }
}
