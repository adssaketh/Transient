package com.demonlab.lune.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Finds album artwork on Apple Music through Apple's public iTunes Search API (https://itunes.apple.com/search).
 * It is the same artwork the Apple Music Artwork Finder website shows, but this class does NOT call that website: its author
 * asks people not to automate it. Only the text the user typed is sent. Call [search] and [downloadCover] from a background thread.
 *
 * NOT CONFIRMED from the build environment (no access to Apple's servers there): the exact response fields used below
 * (collectionName, artistName, releaseDate, artworkUrl100), the "100x100bb" size part of the artwork address that [sized]
 * rewrites, and Apple's request limit. Test on a device before shipping. Nothing is written to a song or playlist by this
 * class; the screens only add the downloaded file as a pending cover and the user decides whether to save.
 */
object AppleMusicArtworkClient {
    enum class Status { FOUND, NOT_FOUND, NETWORK_ERROR }

    /** One album result. [thumbUrl] is the small picture Apple returns; the large one is made from it by [downloadCover]. */
    class Match(
        val album: String,
        val artist: String,
        val year: Int?,
        val thumbUrl: String
    )

    class Result(val status: Status, val matches: List<Match> = emptyList(), val detail: String? = null)

    private const val TAG = "AppleMusicArtwork"
    private const val API = "https://itunes.apple.com/search"
    private const val USER_AGENT = "Transient/1.0 ( https://github.com/adssaketh/Transient )"
    private const val MAX_RESULTS = 12
    // a polite gap between searches (Apple's real limit is not confirmed)
    private const val MIN_GAP_MS = 1500L
    // largest picture first; the first size that downloads is used
    private val SIZES = intArrayOf(1200, 600)

    private val gate = Any()
    private var lastCall = 0L

    fun search(term: String): Result {
        val clean = term.trim()
        if (clean.isBlank()) return Result(Status.NOT_FOUND)
        val country = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"
        val storefronts = if (country == "US") listOf("US") else listOf(country, "US")
        var problem: String? = null
        var networkProblem = false
        for (storefront in storefronts) {
            try {
                val body = get("$API?term=${enc(clean)}&media=music&entity=album&limit=$MAX_RESULTS&country=$storefront")
                val matches = parse(JSONObject(body))
                if (matches.isNotEmpty()) return Result(Status.FOUND, matches)
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
                networkProblem = true
                problem = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
                DiagnosticLogger.note("APPLE ARTWORK search failed: $problem")
            }
        }
        return Result(if (networkProblem) Status.NETWORK_ERROR else Status.NOT_FOUND, detail = problem)
    }

    /**
     * Downloads the largest available picture of [match] into the app's cache and returns the file, or null when none could be
     * downloaded. Several files can exist at once, because the cover sheets keep earlier picks until Save; files older than a
     * day are removed here.
     */
    fun downloadCover(context: Context, match: Match): File? {
        val addresses = SIZES.map { sized(match.thumbUrl, it) } + match.thumbUrl
        for (address in addresses.distinct()) {
            val bytes = try { fetchImage(address) } catch (e: Exception) {
                Log.w(TAG, "cover download failed: ${e.message}")
                null
            } ?: continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
            val dir = File(context.cacheDir, "apple_cover").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            return file
        }
        return null
    }

    private fun parse(root: JSONObject): List<Match> {
        val results = root.optJSONArray("results") ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Match>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val thumb = text(item, "artworkUrl100") ?: continue
            if (!seen.add(thumb)) continue
            val album = text(item, "collectionName") ?: continue
            val artist = text(item, "artistName") ?: ""
            val year = text(item, "releaseDate")?.take(4)?.toIntOrNull()?.takeIf { it > 0 }
            out.add(Match(album, artist, year, thumb))
        }
        return out
    }

    /** "…/100x100bb.jpg" -> "…/1200x1200bb.jpg". An address that does not end that way is returned unchanged. */
    private fun sized(url: String, px: Int): String =
        url.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE)) { "/${px}x${px}bb.${it.groupValues[2]}" }

    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun get(url: String): String {
        synchronized(gate) {
            val wait = lastCall + MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code != 200) {
                DiagnosticLogger.note("APPLE ARTWORK answered HTTP $code | $url")
                throw java.io.IOException("HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchImage(address: String): ByteArray? {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            return bytes.takeIf { it.size in 1024..12_000_000 }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
