package com.demonlab.lune.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Finds the correct tags for a song on MusicBrainz (https://musicbrainz.org, a free community music database) and the cover
 * picture on the Cover Art Archive (https://coverartarchive.org). Neither needs an account or a key. Only the song's title,
 * artist and length are sent. Call [search] and [downloadCover] from a background thread.
 *
 * MusicBrainz asks clients to make at most one request per second and to name themselves in the User-Agent; both are done here.
 * Nothing is written to the song by this class: the Edit screen fills its fields and the user decides whether to save.
 */
object MusicBrainzClient {
    enum class Status { FOUND, NOT_FOUND, NETWORK_ERROR }

    /** One possible answer: a recording on one release (album). Fields MusicBrainz did not give are null. */
    class Match(
        val title: String,
        val artist: String,
        val album: String?,
        val albumArtist: String?,
        val year: Int?,
        val trackNumber: Int?,
        val releaseId: String?,
        val releaseGroupId: String?
    )

    class Result(val status: Status, val matches: List<Match> = emptyList(), val detail: String? = null)

    private const val TAG = "MusicBrainzClient"
    private const val API = "https://musicbrainz.org/ws/2"
    private const val CAA = "https://coverartarchive.org"
    // the form MusicBrainz asks for: "Application/version ( contact address )"
    private const val USER_AGENT = "Transient/1.0 ( https://github.com/adssaketh/Transient )"
    private const val MIN_SCORE = 70
    private const val MAX_MATCHES = 10

    private val gate = Any()
    private var lastMusicBrainzCall = 0L

    fun search(song: Song): Result {
        val title = cleanTitle(song.title)
        if (title.isBlank()) return Result(Status.NOT_FOUND)
        val artist = ArtistNames.singers(song.artist).firstOrNull() ?: ""
        val base = buildString {
            append("recording:\"").append(escape(title)).append('"')
            if (artist.isNotBlank()) append(" AND artist:\"").append(escape(artist)).append('"')
        }
        // title + artist first; if that finds nothing, the title alone (the first singer in the tag is not always the credited one).
        // The song's length is used to rank the answers, not to filter them, which keeps the search simple.
        val queries = ArrayList<String>()
        queries.add(base)
        if (artist.isNotBlank()) queries.add("recording:\"${escape(title)}\"")

        var networkProblem = false
        var problem: String? = null
        for (query in queries) {
            try {
                val body = musicBrainzGet("$API/recording?query=${enc(query)}&fmt=json&limit=15")
                val matches = parse(JSONObject(body), song.duration)
                if (matches.isNotEmpty()) return Result(Status.FOUND, matches)
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
                networkProblem = true
                problem = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
                DiagnosticLogger.note("MUSICBRAINZ search failed: $problem")
            }
        }
        return Result(if (networkProblem) Status.NETWORK_ERROR else Status.NOT_FOUND, detail = problem)
    }

    /** Downloads the front cover of [match] into the app's cache and returns the file, or null when there is no picture. */
    fun downloadCover(context: Context, match: Match): File? {
        val addresses = ArrayList<String>()
        match.releaseId?.let {
            addresses.add("$CAA/release/$it/front-1200")
            addresses.add("$CAA/release/$it/front-500")
        }
        // the release itself may have no picture while another release of the same album has one
        match.releaseGroupId?.let {
            addresses.add("$CAA/release-group/$it/front-1200")
            addresses.add("$CAA/release-group/$it/front-500")
        }
        for (address in addresses) {
            val bytes = try { fetchImage(address) } catch (e: Exception) {
                Log.w(TAG, "cover download failed: ${e.message}")
                null
            } ?: continue
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
            val dir = File(context.cacheDir, "online_cover").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            return file
        }
        return null
    }

    private fun parse(root: JSONObject, wantedMs: Long): List<Match> {
        val recordings = root.optJSONArray("recordings") ?: return emptyList()
        class Ranked(val match: Match, val score: Int, val gap: Int, val rank: Int, val date: String)
        val ranked = ArrayList<Ranked>()
        val seenReleases = HashSet<String>()
        for (i in 0 until recordings.length()) {
            val rec = recordings.optJSONObject(i) ?: continue
            val score = rec.optInt("score", 0)
            if (score < MIN_SCORE) continue
            val title = text(rec, "title") ?: continue
            val artist = creditName(rec.optJSONArray("artist-credit")) ?: continue
            // how far this recording's length is from the song's, in steps of 5 s (unknown = a middle value)
            val recordingMs = if (rec.isNull("length")) 0L else rec.optLong("length", 0L)
            val gap = if (wantedMs > 0 && recordingMs > 0) minOf(abs(recordingMs - wantedMs) / 5000L, 20L).toInt() else 10
            val releases = rec.optJSONArray("releases")
            if (releases == null || releases.length() == 0) {
                ranked.add(Ranked(Match(title, artist, null, null, null, null, null, null), score, gap, 9, ""))
                continue
            }
            for (j in 0 until releases.length()) {
                val rel = releases.optJSONObject(j) ?: continue
                val releaseId = text(rel, "id")
                if (releaseId != null && !seenReleases.add(releaseId)) continue
                val group = rel.optJSONObject("release-group")
                val date = text(rel, "date") ?: ""
                val year = date.take(4).toIntOrNull()?.takeIf { it > 0 }
                val match = Match(
                    title = title,
                    artist = artist,
                    album = text(rel, "title"),
                    albumArtist = creditName(rel.optJSONArray("artist-credit")),
                    year = year,
                    trackNumber = trackNumber(rel),
                    releaseId = releaseId,
                    releaseGroupId = group?.let { text(it, "id") }
                )
                // a normal studio album before a compilation, a live recording or a single
                val isAlbum = group?.let { text(it, "primary-type") } == "Album"
                val plain = group?.optJSONArray("secondary-types")?.length().let { it == null || it == 0 }
                val official = text(rel, "status") == "Official"
                val rank = (if (isAlbum) 0 else 2) + (if (plain) 0 else 4) + (if (official) 0 else 1)
                ranked.add(Ranked(match, score, gap, rank, date))
            }
        }
        return ranked
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.gap }.thenBy { it.rank }.thenBy { if (it.date.isBlank()) "9999" else it.date })
            .take(MAX_MATCHES)
            .map { it.match }
    }

    /** The song's number on its release: the track's own number if it is a plain number, else its place in the list. */
    private fun trackNumber(release: JSONObject): Int? {
        val media = release.optJSONArray("media") ?: return null
        for (i in 0 until media.length()) {
            val medium = media.optJSONObject(i) ?: continue
            val tracks = medium.optJSONArray("track") ?: continue
            val first = tracks.optJSONObject(0) ?: continue
            text(first, "number")?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            if (!medium.isNull("track-offset")) return medium.optInt("track-offset", -1).takeIf { it >= 0 }?.plus(1)
        }
        return null
    }

    private fun creditName(credit: JSONArray?): String? {
        if (credit == null || credit.length() == 0) return null
        val out = StringBuilder()
        for (i in 0 until credit.length()) {
            val part = credit.optJSONObject(i) ?: continue
            val name = text(part, "name") ?: part.optJSONObject("artist")?.let { text(it, "name") } ?: continue
            out.append(name)
            text(part, "joinphrase")?.let { out.append(it) }
        }
        return out.toString().trim().takeIf { it.isNotBlank() }
    }

    // optString() would turn a JSON null into the text "null", so nulls are checked first
    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    /**
     * One MusicBrainz request, at least 1.1 s after the one before. MusicBrainz answers 503 when it is busy or when too many
     * requests come from one address (a mobile network shares one address between many phones), so a 502/503/504 is tried
     * again up to two more times, after the pause MusicBrainz asks for (Retry-After) or 2 s. Returns the body; any other
     * problem throws an exception.
     */
    private fun musicBrainzGet(url: String): String {
        var lastCode = 0
        var pauseMs = 2000L
        for (attempt in 1..3) {
            synchronized(gate) {
                val wait = lastMusicBrainzCall + 1100L - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastMusicBrainzCall = System.currentTimeMillis()
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code == 200) {
                    return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                lastCode = code
                Log.w(TAG, "MusicBrainz answered HTTP $code (attempt $attempt of 3)")
                DiagnosticLogger.note("MUSICBRAINZ answered HTTP $code (attempt $attempt of 3) | ${describeAnswer(connection)} | $url")
                if (code != 502 && code != 503 && code != 504) throw IOException("HTTP $code")
                val asked = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                pauseMs = (asked ?: 2L).coerceIn(1L, 6L) * 1000L
            } finally {
                connection.disconnect()
            }
            if (attempt < 3) Thread.sleep(pauseMs)
        }
        throw IOException("HTTP $lastCode")
    }

    /**
     * Downloads a picture, following the redirect from coverartarchive.org to archive.org by hand: Android does not follow a
     * redirect that goes from https to http, so an http address is upgraded to https first.
     */
    private fun fetchImage(start: String): ByteArray? {
        var address = start
        repeat(5) {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 8000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                when (code) {
                    200 -> {
                        val bytes = connection.inputStream.use { it.readBytes() }
                        return bytes.takeIf { it.size in 1024..8_000_000 }
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: return null
                        address = URL(URL(address), location).toString().replaceFirst("http://", "https://")
                    }
                    else -> return null // 404: no picture for this release
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
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

    /** Inside a quoted Lucene phrase only the quote and the backslash need a backslash in front. */
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** "01. Song name" -> "Song name": a track number copied into the title would stop the search from matching. */
    private fun cleanTitle(raw: String): String = raw.trim().replace(Regex("^\\d{1,3}\\s*[.\\-)]\\s*"), "").trim()
}
