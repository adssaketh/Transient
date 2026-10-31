package com.demonlab.lune.tools

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Looks up lyrics for a song on LRCLIB (https://lrclib.net), a free community lyrics database that needs no account.
 * Only the song's title, artist, album and length are sent. Call [fetch] from a background thread.
 *
 * It asks for an exact match first (title + artist + album + length), then falls back to a search. A search hit is only
 * accepted when its length is within 3 seconds of the song, so a different version is not used by mistake.
 * Time-synced lyrics are preferred over plain text.
 */
object LrclibClient {
    enum class Status { FOUND_SYNCED, FOUND_PLAIN, INSTRUMENTAL, NOT_FOUND, NETWORK_ERROR }

    class Result(val status: Status, val lyrics: String? = null, val detail: String? = null)

    private const val BASE = "https://lrclib.net/api"
    private const val TAG = "LrclibClient"
    private const val MAX_LENGTH_DIFFERENCE_SECONDS = 3

    private class Hit(val synced: String?, val plain: String?, val instrumental: Boolean, val seconds: Double?)

    private class Reply(val code: Int, val body: String?)

    fun fetch(song: Song): Result {
        val title = cleanTitle(song.title)
        if (title.isBlank()) return Result(Status.NOT_FOUND)
        val artist = ArtistNames.singers(song.artist).firstOrNull() ?: song.artist.trim()
        val album = song.album.trim().takeIf { it.isNotBlank() && !it.startsWith("<") }
        val seconds = if (song.duration > 0) ((song.duration + 500L) / 1000L).toInt() else 0

        var networkProblem = false
        var problem: String? = null
        var instrumental = false
        var plain: String? = null

        // remember why a request failed, and write it into the trace so a bug report shows it
        fun fail(what: String, why: String) {
            networkProblem = true
            problem = "$what: $why"
            DiagnosticLogger.note("LRCLIB $what failed: $why")
        }

        fun consider(hit: Hit?): String? {
            if (hit == null) return null
            if (hit.instrumental) instrumental = true
            if (!hit.synced.isNullOrBlank()) return hit.synced
            if (plain == null && !hit.plain.isNullOrBlank()) plain = hit.plain
            return null
        }

        // 1. exact match
        if (album != null && seconds > 0 && artist.isNotBlank()) {
            try {
                val reply = httpGet(
                    "$BASE/get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(album)}&duration=$seconds"
                )
                if (reply.code == 200 && reply.body != null) {
                    consider(parseHit(JSONObject(reply.body)))?.let { return Result(Status.FOUND_SYNCED, it) }
                } else if (reply.code != 404) {
                    fail("exact lookup", "HTTP ${reply.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "exact lookup failed: ${e.message}")
                fail("exact lookup", describe(e))
            }
        }

        // 2. search (title + artist, then one free-text query)
        val searches = buildList {
            if (artist.isNotBlank()) add("$BASE/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            add("$BASE/search?q=${enc((title + " " + artist).trim())}")
        }
        for (url in searches) {
            try {
                val reply = httpGet(url)
                if (reply.code != 200 || reply.body == null) {
                    if (reply.code != 404) fail("search", if (reply.code == 200) "empty reply" else "HTTP ${reply.code}")
                    continue
                }
                val hits = parseHits(JSONArray(reply.body))
                    .filter { seconds <= 0 || it.seconds == null || abs(it.seconds - seconds) <= MAX_LENGTH_DIFFERENCE_SECONDS }
                    .sortedBy { if (seconds > 0 && it.seconds != null) abs(it.seconds - seconds) else 0.0 }
                // a synced hit anywhere in the list wins over plain text
                hits.firstOrNull { !it.synced.isNullOrBlank() }?.let { return Result(Status.FOUND_SYNCED, it.synced) }
                hits.forEach { consider(it) }
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
                fail("search", describe(e))
            }
        }

        plain?.let { return Result(Status.FOUND_PLAIN, it) }
        if (instrumental) return Result(Status.INSTRUMENTAL)
        return Result(if (networkProblem) Status.NETWORK_ERROR else Status.NOT_FOUND, detail = problem)
    }

    private fun parseHits(array: JSONArray): List<Hit> {
        val list = ArrayList<Hit>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            parseHit(o)?.let { list.add(it) }
        }
        return list
    }

    private fun parseHit(o: JSONObject): Hit? {
        // optString() would turn a JSON null into the text "null", so nulls are checked first
        fun text(key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
        val seconds = if (o.isNull("duration")) null else o.optDouble("duration", Double.NaN).takeIf { !it.isNaN() }
        return Hit(text("syncedLyrics"), text("plainLyrics"), o.optBoolean("instrumental", false), seconds)
    }

    /**
     * One request. A "bad gateway / unavailable / timeout" answer (LRCLIB says "the server is busy, please retry in a moment")
     * is tried again, up to three attempts in all, after 1.5 s and then 3 s.
     */
    private fun httpGet(url: String): Reply {
        var reply = httpGetOnce(url)
        var attempt = 1
        while (reply.code in 502..504 && attempt < 3) {
            DiagnosticLogger.note("LRCLIB answered HTTP ${reply.code}, trying again (attempt ${attempt + 1} of 3)")
            Thread.sleep(1500L * attempt)
            reply = httpGetOnce(url)
            attempt++
        }
        return reply
    }

    private fun httpGetOnce(url: String): Reply {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.setRequestProperty("User-Agent", "Transient/1.0 (Android music player; https://github.com/adssaketh/Transient)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299 && code != 404) {
                DiagnosticLogger.note("LRCLIB HTTP $code | ${describeAnswer(connection)} | $url")
            }
            val body = if (code in 200..299) {
                try {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } catch (e: IOException) {
                    null
                }
            } else null
            return Reply(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** For the trace: who answered and the start of an error answer, which usually says why the request was refused. */
    private fun describeAnswer(connection: HttpURLConnection): String {
        val body = try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.replace(Regex("\\s+"), " ")?.take(300)
        } catch (e: Exception) {
            null
        }
        return "server=${connection.getHeaderField("Server")} retry-after=${connection.getHeaderField("Retry-After")} body=${body ?: "(none)"}"
    }

    /** A short description of an error, e.g. "UnknownHostException: Unable to resolve host". */
    private fun describe(e: Exception): String = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")

    /** "01. Song name" -> "Song name": track numbers copied into a title tag would stop the lookup from matching. */
    private fun cleanTitle(raw: String): String = raw.trim().replace(Regex("^\\d{1,3}\\s*[.\\-)]\\s*"), "").trim()
}
