package com.demonlab.lune.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/** Splits "Karthik, Shreya Ghoshal" into the singers it names, and matches a song to one singer. */
object ArtistNames {
    private val separator = Regex(
        "\\s*(?:,|&|;|/|\\+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\band\\b|\\bwith\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val unknown = setOf("<unknown>", "unknown", "unknown artist", "various artists", "various")

    fun singers(raw: String): List<String> =
        raw.split(separator).map { it.trim() }.filter { it.length >= 2 && it.lowercase(Locale.ROOT) !in unknown }

    fun key(name: String): String = name.trim().lowercase(Locale.ROOT)

    fun sings(rawArtist: String, singer: String): Boolean {
        val wanted = key(singer)
        return singers(rawArtist).any { key(it) == wanted }
    }
}

/** What is known about one artist: a short bio, a picture link and where they came from. */
data class ArtistInfo(
    val name: String,
    val title: String?,
    val bio: String?,
    val imageUrl: String?,
    val pageUrl: String?,
    val source: String?,
    val found: Boolean,
    val fetchedAt: Long
)

/**
 * Artist photos and short bios.
 *
 * Online (only when "Artist photos & info from the internet" is on): the artist's name is looked up on Wikipedia for a
 * short bio and a picture, and on Deezer when Wikipedia has no picture. Nothing else is sent. Results are kept on the
 * phone, so a name is only looked up again after a few weeks. Photos the user adds by hand always win over online ones.
 */
class ArtistInfoRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val infoFile = File(appContext.filesDir, "artist_info.json")
    private val photoDir = File(appContext.filesDir, "artist_photos").apply { mkdirs() }
    private val aliasFile = File(appContext.filesDir, "artist_aliases.json")
    private val aliases = ConcurrentHashMap<String, String>()
    private val cache = ConcurrentHashMap<String, ArtistInfo>()
    private val gate = Semaphore(3)
    private val _version = MutableStateFlow(0)

    /** Changes whenever anything is stored or a photo is added / removed, so screens can refresh. */
    val version: StateFlow<Int> = _version

    init {
        loadFromDisk()
        loadAliases()
    }

    /** The other name the user told us to search for instead of [name] (e.g. "S. P. Balasubrahmanyam" for "SP Balu"). */
    fun alias(name: String): String? = aliases[ArtistNames.key(name)]

    /** Searches for [lookupName] instead of [name] from now on, and looks it up right away. */
    suspend fun setAlias(name: String, lookupName: String): ArtistInfo? {
        val key = ArtistNames.key(name)
        val cleaned = lookupName.trim()
        if (cleaned.isEmpty() || ArtistNames.key(cleaned) == key) return clearAlias(name)
        aliases[key] = cleaned
        saveAliases()
        cache.remove(key)
        return fetch(name, force = true)
    }

    suspend fun clearAlias(name: String): ArtistInfo? {
        val key = ArtistNames.key(name)
        aliases.remove(key)
        saveAliases()
        cache.remove(key)
        return fetch(name, force = true)
    }

    fun cached(name: String): ArtistInfo? = cache[ArtistNames.key(name)]?.takeIf { it.found }

    /** The photo the user added for this artist, if any. */
    fun manualPhoto(name: String): File? {
        val prefix = photoPrefix(name)
        return photoDir.listFiles()?.filter { it.name.startsWith(prefix) }?.maxByOrNull { it.lastModified() }
    }

    suspend fun fetch(name: String, force: Boolean = false): ArtistInfo? {
        val key = ArtistNames.key(name)
        if (key.isEmpty()) return null
        val existing = cache[key]
        val settings = SettingsManager.getInstance(appContext)
        if (!settings.onlineArtistInfo) return existing?.takeIf { it.found }
        if (!force && existing != null) {
            val age = System.currentTimeMillis() - existing.fetchedAt
            val maxAge = if (existing.found) 30L * DAY else 7L * DAY
            if (age < maxAge) return existing.takeIf { it.found }
        }
        val fresh = gate.withPermit { withContext(Dispatchers.IO) { lookup(name) } }
            ?: return existing?.takeIf { it.found } // no connection: keep what we had and try again next time
        cache[key] = fresh
        saveToDisk()
        _version.value = _version.value + 1
        return fresh.takeIf { it.found }
    }

    /** Stores a picture the user picked (cropped to a square) for [name]. */
    suspend fun setManualPhoto(name: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
            var bitmap = appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext false

            val rotation = try {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (_: Exception) { 0f }
            if (rotation != 0f) {
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
            }

            val side = min(bitmap.width, bitmap.height)
            val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val finalSide = min(800, side)
            val scaled = if (finalSide != side) Bitmap.createScaledBitmap(square, finalSide, finalSide, true) else square

            clearManualPhoto(name, notify = false)
            // A new file name every time, so the image cache never shows the previous picture.
            val target = File(photoDir, photoPrefix(name) + System.currentTimeMillis() + ".jpg")
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            _version.value = _version.value + 1
            true
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not save the picked photo: ${e.message}")
            false
        }
    }

    fun clearManualPhoto(name: String, notify: Boolean = true) {
        val prefix = photoPrefix(name)
        photoDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        if (notify) _version.value = _version.value + 1
    }

    // ------------------------------------------------------------------------------------------------------------

    private fun photoPrefix(name: String) = Integer.toHexString(ArtistNames.key(name).hashCode()) + "_"

    /** null = could not reach the internet at all (nothing is remembered); found=false = looked up, nothing there. */
    private fun lookup(name: String): ArtistInfo? {
        // Search for the name the user chose, if they gave another one; the library name is kept for display.
        val query = aliases[ArtistNames.key(name)] ?: name
        var reached = false
        var title: String? = null
        var bio: String? = null
        var image: String? = null
        var page: String? = null
        var source: String? = null

        fun take(o: JSONObject) {
            title = o.optString("title")
            bio = trimBio(o.optString("extract")).takeIf { it.isNotBlank() }
            image = o.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() }
            page = o.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")?.takeIf { it.isNotBlank() }
            source = "Wikipedia"
        }

        val search = httpGet(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
                enc("\"$query\" singer OR composer OR musician OR vocalist") + "&srlimit=5&format=json&utf8=1"
        )
        if (search != null) {
            reached = true

            // 1) Ask for the page by the name itself: Wikipedia follows its own redirects ("SP Balu" leads to
            //    "S. P. Balasubrahmanyam"), so a hit here needs no further name check.
            val direct = summaryOf(query)
            if (direct != null && isMusician(direct)) take(direct)

            // 2) Otherwise the best search results whose name is close enough (initials and short forms count).
            if (source == null) {
                val hits = runCatching { JSONObject(search).getJSONObject("query").getJSONArray("search") }.getOrNull()
                if (hits != null) {
                    for (i in 0 until hits.length()) {
                        val candidate = hits.getJSONObject(i).optString("title")
                        if (candidate.isBlank() || !nameMatches(candidate, query)) continue
                        val o = summaryOf(candidate) ?: continue
                        if (!isMusician(o)) continue
                        take(o)
                        break
                    }
                }
            }
        }

        // Wikipedia's summary has no thumbnail for some pages; the page-images API often still has the page's lead image.
        val wikiTitle = title
        if (image == null && !wikiTitle.isNullOrBlank()) {
            val pageImages = httpGet(
                "https://en.wikipedia.org/w/api.php?action=query&prop=pageimages&piprop=thumbnail&pithumbsize=800" +
                    "&redirects=1&format=json&formatversion=2&titles=" + enc(wikiTitle)
            )
            if (pageImages != null) {
                image = runCatching {
                    JSONObject(pageImages).getJSONObject("query").getJSONArray("pages").getJSONObject(0)
                        .getJSONObject("thumbnail").getString("source")
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
        }

        if (image == null) {
            val deezer = httpGet("https://api.deezer.com/search/artist?q=${enc(query)}&limit=5")
            if (deezer != null) {
                reached = true
                val data = runCatching { JSONObject(deezer).getJSONArray("data") }.getOrNull()
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val o = data.getJSONObject(i)
                        if (!nameMatches(o.optString("name"), query)) continue
                        val picture = o.optString("picture_xl").ifBlank { o.optString("picture_big") }
                        if (picture.isBlank() || picture.contains("/artist//")) continue
                        image = picture
                        if (source == null) source = "Deezer"
                        break
                    }
                }
            }
        }

        // Keep the picture on the phone: Wikimedia asks apps to say who they are, and the image loader does not.
        val remote = image
        if (remote != null) image = saveOnlinePhoto(name, remote) ?: remote

        if (!reached) return null
        return ArtistInfo(
            name = name,
            title = title,
            bio = bio,
            imageUrl = image,
            pageUrl = page,
            source = source,
            found = bio != null || image != null,
            fetchedAt = System.currentTimeMillis()
        )
    }

    /** Downloads an online picture into the app's own folder and returns its file address, or null if that did not work. */
    private fun saveOnlinePhoto(name: String, url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Transient/1.0 (Android music player; https://github.com/adssaketh/Transient)")
            }
            if (connection.responseCode !in 200..299) {
                Log.w("ArtistInfo", "Picture download failed: HTTP ${connection.responseCode} for $url")
                return null
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bytes.isEmpty() || bytes.size > 8_000_000 || bounds.outWidth <= 0) return null

            val dir = File(appContext.filesDir, "artist_online").apply { mkdirs() }
            val prefix = photoPrefix(name)
            dir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            val file = File(dir, prefix + System.currentTimeMillis() + ".img")
            file.writeBytes(bytes)
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not save the online picture: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun summaryOf(title: String): JSONObject? {
        val body = httpGet(
            "https://en.wikipedia.org/api/rest_v1/page/summary/" + enc(title.trim().replace(' ', '_')).replace("+", "%20")
        ) ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun isMusician(o: JSONObject): Boolean {
        if (o.optString("type") == "disambiguation") return false
        val text = (o.optString("description") + " " + o.optString("extract")).lowercase(Locale.ROOT)
        return MUSIC_WORDS.any { text.contains(it) }
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Transient/1.0 (Android music player; https://github.com)")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    /**
     * Words of a name, lower case. Runs of single letters are joined into one "initials" word, so "S. P. Balasubrahmanyam",
     * "SP Balasubrahmanyam" and "S P Balasubrahmanyam" all become [sp, balasubrahmanyam].
     */
    private fun words(text: String): List<String> {
        val raw = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val initials = StringBuilder()
        for (w in raw) {
            if (w.length == 1) {
                initials.append(w)
            } else {
                if (initials.isNotEmpty()) { out.add(initials.toString()); initials.clear() }
                out.add(w)
            }
        }
        if (initials.isNotEmpty()) out.add(initials.toString())
        return out
    }

    private fun wordMatches(a: String, b: String): Boolean {
        if (a == b) return true
        val shorter = min(a.length, b.length)
        var common = 0
        while (common < shorter && a[common] == b[common]) common++
        // Short forms: "balu" for "balasubrahmanyam", "ram" for "ramu".
        if (common >= 3 && common >= shorter * 3 / 4) return true
        // One slip of spelling: "karthik" / "kartik".
        if (a.length >= 5 && b.length >= 5 && kotlin.math.abs(a.length - b.length) <= 1 && editDistance(a, b) <= 1) return true
        return false
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    /** Does a page / artist title plausibly name the same person as [name]? Initials and short forms are allowed. */
    private fun nameMatches(candidate: String, name: String): Boolean {
        val base = words(candidate.substringBefore(" ("))
        val wanted = words(name)
        if (base.isEmpty() || wanted.isEmpty()) return false
        val matched = wanted.count { w -> base.any { wordMatches(it, w) } }
        // about 60 % of the words must match (rounded up): 1 of 1, 2 of 2, 2 of 3, 3 of 4
        return matched >= 1 && matched >= (wanted.size * 3 + 4) / 5
    }

    private fun trimBio(text: String): String {
        val t = text.trim()
        if (t.length <= 620) return t
        val cut = t.take(620)
        val end = cut.lastIndexOf(". ")
        return if (end > 220) cut.take(end + 1) else cut.trimEnd() + "\u2026"
    }

    private fun loadFromDisk() {
        try {
            if (!infoFile.exists()) return
            val array = JSONArray(infoFile.readText())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                cache[ArtistNames.key(name)] = ArtistInfo(
                    name = name,
                    title = o.optString("title").takeIf { it.isNotBlank() },
                    bio = o.optString("bio").takeIf { it.isNotBlank() },
                    imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
                    pageUrl = o.optString("pageUrl").takeIf { it.isNotBlank() },
                    source = o.optString("source").takeIf { it.isNotBlank() },
                    found = o.optBoolean("found", false),
                    fetchedAt = o.optLong("fetchedAt", 0L)
                )
            }
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not read the saved artist info: ${e.message}")
        }
    }

    private fun loadAliases() {
        try {
            if (!aliasFile.exists()) return
            val o = JSONObject(aliasFile.readText())
            for (k in o.keys()) aliases[k] = o.getString(k)
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not read the saved artist names: ${e.message}")
        }
    }

    @Synchronized
    private fun saveAliases() {
        try {
            val o = JSONObject()
            for ((k, v) in aliases) o.put(k, v)
            aliasFile.writeText(o.toString())
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not save the artist names: ${e.message}")
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            val array = JSONArray()
            for (info in cache.values) {
                array.put(
                    JSONObject()
                        .put("name", info.name)
                        .put("title", info.title ?: "")
                        .put("bio", info.bio ?: "")
                        .put("imageUrl", info.imageUrl ?: "")
                        .put("pageUrl", info.pageUrl ?: "")
                        .put("source", info.source ?: "")
                        .put("found", info.found)
                        .put("fetchedAt", info.fetchedAt)
                )
            }
            infoFile.writeText(array.toString())
        } catch (e: Exception) {
            Log.w("ArtistInfo", "Could not save the artist info: ${e.message}")
        }
    }

    companion object {
        private const val DAY = 24L * 60L * 60L * 1000L
        private val MUSIC_WORDS = listOf(
            "singer", "playback", "composer", "musician", "vocalist", "music director", "songwriter",
            "lyricist", "rapper", "band", "record producer", "music producer"
        )

        @Volatile
        private var instance: ArtistInfoRepository? = null

        fun getInstance(context: Context): ArtistInfoRepository =
            instance ?: synchronized(this) {
                instance ?: ArtistInfoRepository(context.applicationContext).also { instance = it }
            }
    }
}
