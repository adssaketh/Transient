package com.demonlab.lune.ai

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.demonlab.lune.R
import com.demonlab.lune.ai.model.AiMix
import com.demonlab.lune.ai.model.DayType
import com.demonlab.lune.ai.model.MixCategory
import com.demonlab.lune.ai.model.SongInteraction
import com.demonlab.lune.ai.model.TimeOfDay
import com.demonlab.lune.ai.storage.AiTelemetryStorage
import com.demonlab.lune.tools.Song
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class LuneAiEngine private constructor(private val context: Context) {
    private val storage = AiTelemetryStorage(context)
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("LuneAiEngine", "Unhandled exception in AI engine coroutine", throwable)
    }
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    private val _aiMixes = MutableStateFlow<List<AiMix>>(emptyList())
    val aiMixes: StateFlow<List<AiMix>> = _aiMixes.asStateFlow()

    private var lastStartedSongId: Long? = null
    private var lastStartedTimeMs: Long = 0L
    private var previousPlayedSongId: Long? = null

    companion object {
        @Volatile
        private var INSTANCE: LuneAiEngine? = null

        fun getInstance(context: Context): LuneAiEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LuneAiEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1. TELEMETRY & HABIT LEARNING
    // ─────────────────────────────────────────────────────────────

    fun onSongStarted(song: Song) {
        try {
            val now = System.currentTimeMillis()
            val timeOfDay = TimeOfDay.current()
            val isWeekend = DayType.current() == DayType.WEEKEND

            // Record Markov transition from previous track if applicable
            previousPlayedSongId?.let { prevId ->
                if (prevId != song.id && (now - lastStartedTimeMs > 40_000L)) {
                    storage.recordInteraction(prevId) { prevInteraction ->
                        prevInteraction.recordTransitionTo(song.id)
                    }
                }
            }

            // Check for immediate repeat loop
            val isReplay = lastStartedSongId == song.id && (now - lastStartedTimeMs < 35_000L)
            previousPlayedSongId = lastStartedSongId
            lastStartedSongId = song.id
            lastStartedTimeMs = now

            storage.recordInteraction(song.id) { interaction ->
                interaction.playCount++
                interaction.lastPlayedTimestamp = now
                if (isReplay) {
                    interaction.repeatCount++
                }
                if (isWeekend) {
                    interaction.weekendPlays++
                } else {
                    interaction.weekdayPlays++
                }
                when (timeOfDay) {
                    TimeOfDay.MORNING -> interaction.morningPlays++
                    TimeOfDay.AFTERNOON -> interaction.afternoonPlays++
                    TimeOfDay.EVENING -> interaction.eveningPlays++
                    TimeOfDay.NIGHT -> interaction.nightPlays++
                }
                // Reset consecutive skips on clean start
                if (interaction.consecutiveSkips > 0) {
                    interaction.consecutiveSkips = 0
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error onSongStarted: ${e.message}", e)
        }
    }

    fun onSongCompleted(song: Song) {
        try {
            storage.recordInteraction(song.id) { interaction ->
                interaction.fullCompletions++
                interaction.consecutiveSkips = 0
            }
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error onSongCompleted: ${e.message}", e)
        }
    }

    fun onSongSkipped(song: Song, playedSeconds: Long, totalDurationSeconds: Long) {
        try {
            val isFastSkip = playedSeconds < 20 || (totalDurationSeconds > 0 && (playedSeconds.toFloat() / totalDurationSeconds.toFloat()) < 0.15f)
            if (isFastSkip) {
                storage.recordInteraction(song.id) { interaction ->
                    interaction.fastSkips++
                    interaction.consecutiveSkips++
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error onSongSkipped: ${e.message}", e)
        }
    }

    fun onSongFavoriteToggled(song: Song, isFavorite: Boolean) {
        try {
            storage.recordInteraction(song.id) { interaction ->
                interaction.isFavorite = isFavorite
            }
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error onSongFavoriteToggled: ${e.message}", e)
        }
    }

    fun onSongAddedToPlaylist(songId: Long) {
        try {
            storage.recordInteraction(songId) { interaction ->
                interaction.playlistAddCount++
            }
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error onSongAddedToPlaylist: ${e.message}", e)
        }
    }

    fun getSongAffinity(songId: Long): Float {
        return try {
            val now = System.currentTimeMillis()
            val currentTime = TimeOfDay.current()
            val isWeekend = DayType.current() == DayType.WEEKEND
            storage.getInteraction(songId).calculateDynamicScore(now, currentTime, isWeekend)
        } catch (e: Exception) {
            android.util.Log.e("LuneAiEngine", "Error calculating song affinity: ${e.message}", e)
            1.0f
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. MUSICAL HEURISTICS & ACOUSTIC PROFILING
    // ─────────────────────────────────────────────────────────────

    data class AudioProfile(
        val energyScore: Float,       // 0.0 (calm/chill) to 1.0 (energetic/fast)
        val acousticScore: Float,     // 0.0 (electronic/produced) to 1.0 (raw acoustic)
        val isLiveOrAcoustic: Boolean,
        val isFocusFriendly: Boolean
    )

    fun profileSong(song: Song): AudioProfile {
        val titleLower = song.title.lowercase(Locale.getDefault())
        val genreLower = (song.genre ?: "").lowercase(Locale.getDefault())

        var energy = 0.50f
        var acoustic = 0.30f
        var isLiveOrAcoustic = false

        // Acoustic / Live heuristics
        if (titleLower.contains("acoustic") || titleLower.contains("acústic") || 
            titleLower.contains("unplugged") || titleLower.contains("piano version") ||
            genreLower.contains("acoustic") || genreLower.contains("folk")) {
            acoustic += 0.50f
            energy -= 0.25f
            isLiveOrAcoustic = true
        }

        if (titleLower.contains("live") || titleLower.contains("en vivo") || 
            titleLower.contains("en directo") || titleLower.contains("session")) {
            isLiveOrAcoustic = true
        }

        // High Energy heuristics
        if (titleLower.contains("remix") || titleLower.contains("extended") || 
            titleLower.contains("club") || titleLower.contains("speed up") ||
            genreLower.contains("rock") || genreLower.contains("metal") ||
            genreLower.contains("dance") || genreLower.contains("edm") ||
            genreLower.contains("electronic") || genreLower.contains("trap") ||
            genreLower.contains("hardcore")) {
            energy += 0.35f
            acoustic -= 0.20f
        }

        // Low Energy / Chill heuristics
        if (titleLower.contains("lofi") || titleLower.contains("lo-fi") ||
            titleLower.contains("slowed") || titleLower.contains("ambient") ||
            titleLower.contains("instrumental") || genreLower.contains("ambient") ||
            genreLower.contains("classical") || genreLower.contains("jazz") ||
            genreLower.contains("chill")) {
            energy -= 0.30f
            acoustic += 0.25f
        }

        // Duration dynamics
        if (song.duration > 300_000L) { // > 5 min usually more progressive/chill
            energy -= 0.08f
        } else if (song.duration in 120_000L..210_000L) { // 2-3.5 min usually high tempo radio hits
            energy += 0.08f
        }

        energy = energy.coerceIn(0.05f, 0.98f)
        acoustic = acoustic.coerceIn(0.05f, 0.98f)

        val isFocus = acoustic > 0.40f || energy in 0.20f..0.55f || titleLower.contains("instrumental") || titleLower.contains("lo-fi")

        return AudioProfile(
            energyScore = energy,
            acousticScore = acoustic,
            isLiveOrAcoustic = isLiveOrAcoustic,
            isFocusFriendly = isFocus
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 2b. SONG FEATURES, TASTE MODEL AND SIMILARITY
    // ─────────────────────────────────────────────────────────────

    /** Everything the engine compares between two songs, worked out once per song and cached. */
    private class SongFeat(
        val sig: Int,
        val artistKey: String,        // the whole artist field, lower-case
        val artists: Set<String>,     // individual singers: "Karthik, Shreya Ghoshal" -> {karthik, shreya ghoshal}
        val album: String,            // album / movie name without a trailing "(2009)"
        val genre: String,
        val year: Int,
        val script: Int,              // 0 latin, 1 telugu, 2 tamil, 3 devanagari, 4 kannada, 5 malayalam, 6 other
        val energy: Float,
        val acoustic: Float
    )

    private val featCache = ConcurrentHashMap<Long, SongFeat>()
    private val artistSplit = Regex(
        "\\s*(?:,|&|;|/|\\+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\band\\b|\\bwith\\b)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val albumYearSuffix = Regex("\\s*[\\(\\[]\\d{4}[\\)\\]]\\s*$")
    private val unknownNames = setOf("<unknown>", "unknown", "unknown artist", "unknown album", "various artists", "various")

    private fun splitArtists(raw: String): Set<String> =
        raw.split(artistSplit)
            .map { it.trim().lowercase(Locale.getDefault()) }
            .filter { it.length >= 2 && it !in unknownNames }
            .toSet()

    private fun albumKey(raw: String): String {
        val cleaned = raw.trim().replace(albumYearSuffix, "").trim().lowercase(Locale.getDefault())
        return if (cleaned.isEmpty() || cleaned in unknownNames) "" else cleaned
    }

    private fun scriptOf(text: String): Int {
        for (ch in text) {
            if (!ch.isLetter()) continue
            return when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.TELUGU -> 1
                Character.UnicodeBlock.TAMIL -> 2
                Character.UnicodeBlock.DEVANAGARI -> 3
                Character.UnicodeBlock.KANNADA -> 4
                Character.UnicodeBlock.MALAYALAM -> 5
                else -> if (ch.code < 0x250) 0 else 6
            }
        }
        return 0
    }

    private fun featOf(song: Song): SongFeat {
        val sig = java.util.Objects.hash(song.artist, song.album, song.genre, song.title, song.year, song.duration)
        val cached = featCache[song.id]
        if (cached != null && cached.sig == sig) return cached
        val profile = profileSong(song)
        val feat = SongFeat(
            sig = sig,
            artistKey = song.artist.trim().lowercase(Locale.getDefault()),
            artists = splitArtists(song.artist),
            album = albumKey(song.album),
            genre = song.genre?.trim()?.lowercase(Locale.getDefault()).orEmpty(),
            year = song.year,
            script = scriptOf(song.title),
            energy = profile.energyScore,
            acoustic = profile.acousticScore
        )
        featCache[song.id] = feat
        return feat
    }

    /** What the user likes, learned per singer, album, genre and decade from how they really listen. */
    private class TasteProfile(
        val artist: Map<String, Float>,
        val album: Map<String, Float>,
        val genre: Map<String, Float>,
        val decade: Map<Int, Float>
    )

    @Volatile private var taste: TasteProfile? = null
    @Volatile private var tasteBuiltAt = 0L
    @Volatile private var tasteSize = 0

    private fun buildTaste(songs: List<Song>): TasteProfile {
        val artist = HashMap<String, Float>()
        val album = HashMap<String, Float>()
        val genre = HashMap<String, Float>()
        val decade = HashMap<Int, Float>()
        for (song in songs) {
            val interaction = storage.getInteraction(song.id)
            // 0 for never-played songs, positive for loved ones (plays, completions, repeats, favourites, playlists),
            // negative for songs that keep getting skipped.
            var signal = interaction.calculateAffinityScore() - 10f
            if (song.isFavorite && !interaction.isFavorite) signal += 9f
            if (signal == 0f) continue
            val f = featOf(song)
            if (f.artists.isNotEmpty()) {
                val share = signal / f.artists.size
                for (a in f.artists) artist[a] = (artist[a] ?: 0f) + share
            }
            if (f.album.isNotEmpty()) album[f.album] = (album[f.album] ?: 0f) + signal
            if (f.genre.isNotEmpty()) genre[f.genre] = (genre[f.genre] ?: 0f) + signal
            if (f.year > 0) decade[f.year / 10 * 10] = (decade[f.year / 10 * 10] ?: 0f) + signal
        }
        // Squash every total into -1..1 so one obsession cannot drown everything else.
        return TasteProfile(
            artist = artist.mapValues { (_, v) -> v / (abs(v) + 25f) },
            album = album.mapValues { (_, v) -> v / (abs(v) + 25f) },
            genre = genre.mapValues { (_, v) -> v / (abs(v) + 25f) },
            decade = decade.mapValues { (_, v) -> v / (abs(v) + 60f) }
        )
    }

    /** Fresh taste from the whole library (called whenever the mixes are rebuilt). */
    private fun refreshTaste(allSongs: List<Song>): TasteProfile {
        val built = buildTaste(allSongs)
        taste = built
        tasteBuiltAt = System.currentTimeMillis()
        tasteSize = allSongs.size
        return built
    }

    /** The cached taste while it is recent; a list that is only a slice of the library never replaces it. */
    private fun tasteFor(songs: List<Song>): TasteProfile {
        val cached = taste
        val now = System.currentTimeMillis()
        val looksLikeLibrary = songs.size >= tasteSize * 0.8
        if (cached != null && (now - tasteBuiltAt < 60_000L || !looksLikeLibrary)) return cached
        return refreshTaste(songs)
    }

    /** -1 (avoid) .. +1 (loved): how well a song fits the learned taste, even if it has never been played. */
    private fun tasteScore(f: SongFeat, t: TasteProfile): Float {
        var artistTaste = 0f
        if (f.artists.isNotEmpty()) {
            val values = f.artists.map { t.artist[it] ?: 0f }.sortedDescending()
            artistTaste = if (values.size == 1) values[0] else (values[0] + values[1]) / 2f
        }
        val albumTaste = if (f.album.isNotEmpty()) t.album[f.album] ?: 0f else 0f
        val genreTaste = if (f.genre.isNotEmpty()) t.genre[f.genre] ?: 0f else 0f
        val decadeTaste = if (f.year > 0) t.decade[f.year / 10 * 10] ?: 0f else 0f
        return 0.50f * artistTaste + 0.30f * albumTaste + 0.12f * genreTaste + 0.08f * decadeTaste
    }

    /** Ranking score for choosing songs: play history and time of day, plus how well the song fits the taste profile. */
    private fun rank(song: Song): Float {
        val base = getSongAffinity(song.id)
        val t = taste ?: return base
        return (base + 8f * tasteScore(featOf(song), t)).coerceAtLeast(0.1f)
    }

    /** 0..1 how alike two songs are: shared singers, album, genre, era, language and learned "this follows that". */
    private fun similarity(a: SongFeat, b: SongFeat, learned: SongInteraction?, bId: Long): Float {
        var s = 0f

        if (a.artists.isNotEmpty() && b.artists.isNotEmpty()) {
            val shared = a.artists.count { it in b.artists }
            if (shared > 0) s += 0.38f * shared / (a.artists.size + b.artists.size - shared).toFloat()
        } else if (a.artistKey.isNotEmpty() && a.artistKey == b.artistKey) {
            s += 0.38f
        }

        if (a.album.isNotEmpty() && a.album == b.album) s += 0.16f

        if (a.genre.isNotEmpty() && b.genre.isNotEmpty()) {
            if (a.genre == b.genre) s += 0.22f else if (isCompatibleGenre(a.genre, b.genre)) s += 0.14f
        }

        if (a.year > 0 && b.year > 0) {
            val gap = abs(a.year - b.year)
            s += when {
                gap <= 1 -> 0.12f
                gap <= 4 -> 0.08f
                gap <= 9 -> 0.03f
                else -> 0f
            }
        }

        // Same language / script keeps a session coherent; mixing them is a jarring jump.
        s += if (a.script == b.script) 0.08f else -0.08f

        val harmony = 1f - (abs(a.energy - b.energy) * 0.6f + abs(a.acoustic - b.acoustic) * 0.4f)
        s += (harmony * 0.20f).coerceIn(0f, 0.20f)

        val transition = learned?.getTransitionProbabilityTo(bId) ?: 0f
        if (transition > 0f) s += (transition * 0.20f).coerceIn(0f, 0.20f)

        return s.coerceIn(0f, 1f)
    }

    private fun computeSongSimilarity(a: Song, b: Song): Float {
        if (a.id == b.id) return 1.0f
        return similarity(featOf(a), featOf(b), storage.getInteraction(a.id), b.id)
    }

    private val rockFamily = setOf("rock", "alternative", "indie", "metal", "punk", "grunge", "hard rock")
    private val popFamily = setOf("pop", "dance", "electropop", "synthpop", "indie pop", "disco")
    private val electronicFamily = setOf("electronic", "edm", "house", "techno", "synthwave", "ambient", "trance")
    private val urbanFamily = setOf("hip hop", "rap", "trap", "r&b", "soul", "reggaeton", "urban")
    private val chillFamily = setOf("acoustic", "folk", "classical", "instrumental", "ambient", "lo-fi", "chill", "jazz")

    private fun isCompatibleGenre(g1: String, g2: String): Boolean {
        return (g1 in rockFamily && g2 in rockFamily) ||
               (g1 in popFamily && g2 in popFamily) ||
               (g1 in electronicFamily && g2 in electronicFamily) ||
               (g1 in urbanFamily && g2 in urbanFamily) ||
               (g1 in chillFamily && g2 in chillFamily)
    }

    // ─────────────────────────────────────────────────────────────
    // 3. SMART SHUFFLE (HARMONIC FLOW REORDERING)
    // ─────────────────────────────────────────────────────────────

    private class ShuffleEntry(
        val song: Song,
        val feat: SongFeat,
        val rank: Float
    )

    /**
     * Orders [songs] so neighbouring tracks flow into each other: shared singers / album / era / language, a similar
     * mood, songs the user loves (or whose singer / album they love) first, and no long runs of one artist or album.
     *
     * Everything per-song is worked out once, and each pick only scores a random sample of the remaining songs, so a
     * ~1000 song library takes a fraction of a second (the first version compared every pair on the UI thread, which
     * froze the app when "Start Smart Shuffle" was tapped).
     */
    fun generateSmartShuffle(songs: List<Song>, startingSong: Song? = null): List<Song> {
        if (songs.size <= 2) return songs

        val tasteProfile = tasteFor(songs)
        val entries = ArrayList<ShuffleEntry>(songs.size)
        for (song in songs) {
            val feat = featOf(song)
            val rank = (getSongAffinity(song.id) + 8f * tasteScore(feat, tasteProfile)).coerceAtLeast(0.1f)
            entries.add(ShuffleEntry(song, feat, rank))
        }

        val random = Random.Default
        val seedAt = startingSong?.let { start -> entries.indexOfFirst { it.song.id == start.id }.takeIf { it >= 0 } }
            ?: entries.indices.maxByOrNull { entries[it].rank }
            ?: 0

        // Order of the pool does not matter, so removing swaps with the last element (O(1)).
        val pool = ArrayList<ShuffleEntry>(entries)
        var current = pool[seedAt]
        pool[seedAt] = pool[pool.size - 1]
        pool.removeAt(pool.size - 1)

        val result = ArrayList<Song>(songs.size)
        result.add(current.song)
        var albumRun = 1
        var artistRun = 1

        fun score(from: ShuffleEntry, learned: SongInteraction, candidate: ShuffleEntry): Float {
            val sim = similarity(from.feat, candidate.feat, learned, candidate.song.id)
            var penalty = 0f
            if (candidate.feat.artistKey.isNotEmpty() && candidate.feat.artistKey == from.feat.artistKey) {
                penalty -= if (artistRun >= 2) 0.55f else 0.30f
            }
            if (candidate.feat.album.isNotEmpty() && candidate.feat.album == from.feat.album && albumRun >= 3) {
                penalty -= 0.30f
            }
            val noise = random.nextFloat() * 0.08f
            return (sim * 0.48f) + (candidate.rank / 45f).coerceIn(0f, 0.44f) + penalty + noise
        }

        val sampleSize = 150
        while (pool.isNotEmpty()) {
            val learned = storage.getInteraction(current.song.id)
            val scored = ArrayList<Pair<Int, Float>>(minOf(sampleSize, pool.size))
            if (pool.size <= sampleSize) {
                for (i in pool.indices) scored.add(i to score(current, learned, pool[i]))
            } else {
                repeat(sampleSize) {
                    val i = random.nextInt(pool.size)
                    scored.add(i to score(current, learned, pool[i]))
                }
            }
            scored.sortByDescending { it.second }

            val pick = scored.take(minOf(3, scored.size)).random(random).first
            val chosen = pool[pick]
            pool[pick] = pool[pool.size - 1]
            pool.removeAt(pool.size - 1)

            albumRun = if (chosen.feat.album.isNotEmpty() && chosen.feat.album == current.feat.album) albumRun + 1 else 1
            artistRun = if (chosen.feat.artistKey.isNotEmpty() && chosen.feat.artistKey == current.feat.artistKey) artistRun + 1 else 1
            result.add(chosen.song)
            current = chosen
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // 4. DISCOVERY MODE GENERATION (EXPLORING UNHEARD GEMS)
    // ─────────────────────────────────────────────────────────────

    fun generateDiscoveryQueue(allSongs: List<Song>, seedSong: Song? = null): List<Song> {
        if (allSongs.isEmpty()) return emptyList()

        val tasteProfile = tasteFor(allSongs)

        // Partition songs into favorites/familiar vs unexplored
        val familiar = allSongs.filter { getSongAffinity(it.id) >= 12f }
        val unexplored = allSongs.filter { 
            val interaction = storage.getInteraction(it.id)
            interaction.playCount <= 1 && !interaction.isFavorite
        }

        if (unexplored.isEmpty()) {
            return generateSmartShuffle(allSongs, seedSong)
        }

        val seed = seedSong ?: familiar.maxByOrNull { rank(it) } ?: allSongs.first()
        val result = mutableListOf<Song>(seed)

        // Find unexplored tracks that have high similarity to user's favorite tracks
        // Comparing against the 40 strongest favourites is plenty and keeps this fast on big libraries.
        val reference = familiar.sortedByDescending { rank(it) }.take(40)
        val rankedUnexplored = unexplored.map { candidate ->
            val maxSimToFavorites = reference.maxOfOrNull { computeSongSimilarity(it, candidate) } ?: 0.3f
            // Unheard songs by singers / from albums the user already loves rise to the top.
            val fit = tasteScore(featOf(candidate), tasteProfile)
            candidate to (maxSimToFavorites * 0.65f + fit * 0.35f)
        }.sortedByDescending { it.second }

        val familiarPool = (familiar - seed).toMutableList()
        val unexploredPool = rankedUnexplored.map { it.first }.toMutableList()

        // Interleave: 2 familiar tracks -> 1 high-confidence discovery track
        var flip = 0
        while (familiarPool.isNotEmpty() || unexploredPool.isNotEmpty()) {
            if (flip % 3 == 2 && unexploredPool.isNotEmpty()) {
                val nextDisc = unexploredPool.removeAt(0)
                result.add(nextDisc)
            } else if (familiarPool.isNotEmpty()) {
                val last = result.last()
                val nextFam = familiarPool.maxByOrNull { computeSongSimilarity(last, it) } ?: familiarPool.first()
                familiarPool.remove(nextFam)
                result.add(nextFam)
            } else if (unexploredPool.isNotEmpty()) {
                result.add(unexploredPool.removeAt(0))
            }
            flip++
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // 5. SMART NEXT PREDICTION (FOR AUTO-PLAY & INFINITE RADIO)
    // ─────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────
    // 6. DEEP AI MIX GENERATION (EXPANDED DIVERSITY)
    // ─────────────────────────────────────────────────────────────

    fun refreshMixes(allSongs: List<Song>) {
        if (allSongs.isEmpty()) return

        engineScope.launch {
            try {
                val generated = mutableListOf<AiMix>()
                // Learn what the user likes (singers, albums, genres, eras) from their real listening before building mixes.
                refreshTaste(allSongs)
                val currentTime = TimeOfDay.current()
                val dayType = DayType.current()
                val isWeekend = dayType == DayType.WEEKEND

                // 1. Daily AI Flow (Mix del Día)
                val dailySongs = allSongs
                .sortedByDescending { rank(it) }
                .take(32)
            if (dailySongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_daily_flow",
                        title = context.getString(R.string.ai_mix_daily_title),
                        subtitle = context.getString(R.string.ai_mix_daily_sub),
                        category = MixCategory.DAILY_FLOW,
                        songs = generateSmartShuffle(dailySongs),
                        gradientColors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899)),
                        iconName = "auto_awesome",
                        description = "Built from your real-time taste, listening hours and smooth harmonic transitions."
                    )
                )
            }

            // 2. Modo Descubrimiento (Deep Discovery Mix)
            val discoverySongs = generateDiscoveryQueue(allSongs).take(28)
            if (discoverySongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_discovery_flow",
                        title = context.getString(R.string.ai_mix_discovery_title),
                        subtitle = context.getString(R.string.ai_mix_discovery_sub),
                        category = MixCategory.DISCOVERY,
                        songs = discoverySongs,
                        gradientColors = listOf(Color(0xFF0D9488), Color(0xFF059669), Color(0xFF10B981)),
                        iconName = "explore",
                        description = "Explore songs in your library that closely match your taste."
                    )
                )
            }

            // 3. Energy Boost (Ritmo y Cardio)
            val filteredEnergy = allSongs.filter { song ->
                val profile = profileSong(song)
                profile.energyScore > 0.62f || (song.duration < 230_000L && getSongAffinity(song.id) > 8f)
            }.sortedByDescending { rank(it) }.take(25)

            val energySongs = if (filteredEnergy.size < 15 && allSongs.size >= 4) {
                val existingIds = filteredEnergy.map { it.id }.toSet()
                val additional = allSongs.filter { it.id !in existingIds }
                    .sortedByDescending { profileSong(it).energyScore }
                    .take(25 - filteredEnergy.size)
                filteredEnergy + additional
            } else {
                filteredEnergy
            }

            if (energySongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_energy_boost",
                        title = context.getString(R.string.ai_mix_energy_title),
                        subtitle = context.getString(R.string.ai_mix_energy_sub),
                        category = MixCategory.ENERGY,
                        songs = generateSmartShuffle(energySongs),
                        gradientColors = listOf(Color(0xFFF97316), Color(0xFFEF4444), Color(0xFFEC4899)),
                        iconName = "bolt",
                        description = "Songs with lively beats and energetic tempos from your library."
                    )
                )
            }

            // 4. Chill & Focus / Relajación
            val filteredChill = allSongs.filter { song ->
                val profile = profileSong(song)
                profile.energyScore < 0.45f || profile.isFocusFriendly
            }.sortedByDescending { rank(it) }.take(25)

            val chillSongs = if (filteredChill.size < 15 && allSongs.size >= 4) {
                val existingIds = filteredChill.map { it.id }.toSet()
                val additional = allSongs.filter { it.id !in existingIds }
                    .sortedBy { profileSong(it).energyScore }
                    .take(25 - filteredChill.size)
                filteredChill + additional
            } else {
                filteredChill
            }

            if (chillSongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_chill_flow",
                        title = context.getString(R.string.ai_mix_chill_title),
                        subtitle = context.getString(R.string.ai_mix_chill_sub),
                        category = MixCategory.CHILL,
                        songs = generateSmartShuffle(chillSongs),
                        gradientColors = listOf(Color(0xFF06B6D4), Color(0xFF3B82F6), Color(0xFF6366F1)),
                        iconName = "spa",
                        description = "Acoustic sounds and slow textures for quiet moments."
                    )
                )
            }

            // 5. Contexto Temporal & Fin de Semana
            val timeName = when {
                isWeekend -> context.getString(R.string.ai_mix_weekend_title)
                currentTime == TimeOfDay.MORNING -> context.getString(R.string.ai_mix_morning_title)
                currentTime == TimeOfDay.AFTERNOON -> context.getString(R.string.ai_mix_afternoon_title)
                currentTime == TimeOfDay.EVENING -> context.getString(R.string.ai_mix_evening_title)
                else -> context.getString(R.string.ai_mix_night_title)
            }
            val timeSub = when {
                isWeekend -> context.getString(R.string.ai_mix_weekend_sub)
                currentTime == TimeOfDay.MORNING -> context.getString(R.string.ai_mix_morning_sub)
                currentTime == TimeOfDay.AFTERNOON -> context.getString(R.string.ai_mix_afternoon_sub)
                currentTime == TimeOfDay.EVENING -> context.getString(R.string.ai_mix_evening_sub)
                else -> context.getString(R.string.ai_mix_night_sub)
            }
            val timeGradients = when (currentTime) {
                TimeOfDay.MORNING -> listOf(Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF06B6D4))
                TimeOfDay.AFTERNOON -> listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFF06B6D4))
                TimeOfDay.EVENING -> listOf(Color(0xFFF97316), Color(0xFF8B5CF6), Color(0xFF4F46E5))
                TimeOfDay.NIGHT -> listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4C1D95))
            }

            val timeSongs = allSongs.sortedByDescending { song ->
                val interaction = storage.getInteraction(song.id)
                interaction.calculateDynamicScore(System.currentTimeMillis(), currentTime, isWeekend) +
                    (taste?.let { 8f * tasteScore(featOf(song), it) } ?: 0f)
            }.take(25)

            if (timeSongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_time_mix",
                        title = timeName,
                        subtitle = timeSub,
                        category = MixCategory.TIME_OF_DAY,
                        songs = generateSmartShuffle(timeSongs),
                        gradientColors = timeGradients,
                        iconName = if (currentTime == TimeOfDay.NIGHT) "bedtime" else "wb_sunny",
                        description = "Adapted to the current time of day, based on your past habits."
                    )
                )
            }

            // 6. Joyas Olvidadas (Forgotten Gems)
            val now = System.currentTimeMillis()
            val twoWeeksAgo = now - (14L * 24L * 60L * 60L * 1000L)
            val forgottenSongs = allSongs.filter { song ->
                val interaction = storage.getInteraction(song.id)
                (interaction.isFavorite || interaction.playCount >= 3) &&
                (interaction.lastPlayedTimestamp in 1..twoWeeksAgo || interaction.lastPlayedTimestamp == 0L)
            }.shuffled().take(22)

            if (forgottenSongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_forgotten_gems",
                        title = context.getString(R.string.ai_mix_forgotten_title),
                        subtitle = context.getString(R.string.ai_mix_forgotten_sub),
                        category = MixCategory.FORGOTTEN_GEMS,
                        songs = forgottenSongs,
                        gradientColors = listOf(Color(0xFF10B981), Color(0xFF059669), Color(0xFF047857)),
                        iconName = "history",
                        description = "Rediscover highly rated tracks from your history that you have left behind."
                    )
                )
            }

            // 7. Sesiones Acústicas / En Directo (si existen en la biblioteca)
            val acousticLiveSongs = allSongs.filter { profileSong(it).isLiveOrAcoustic }
            if (acousticLiveSongs.size >= 4) {
                generated.add(
                    AiMix(
                        id = "ai_acoustic_live",
                        title = context.getString(R.string.ai_mix_acoustic_title),
                        subtitle = context.getString(R.string.ai_mix_acoustic_sub),
                        category = MixCategory.ACOUSTIC_LIVE,
                        songs = generateSmartShuffle(acousticLiveSongs),
                        gradientColors = listOf(Color(0xFFD97706), Color(0xFFB45309), Color(0xFF78350F)),
                        iconName = "mic_external_on",
                        description = "Smart detection of acoustic tracks and live recordings."
                    )
                )
            }

            // 8. Artist radio: the singer the user loves most (collaborations included), plus songs that sound alike
            val tasteNow = taste
            val bySinger = HashMap<String, MutableList<Song>>()
            for (song in allSongs) {
                for (singer in featOf(song).artists) bySinger.getOrPut(singer) { mutableListOf() }.add(song)
            }
            val topSinger = bySinger.entries
                .filter { it.value.size >= 3 }
                .maxByOrNull { entry ->
                    val liked = tasteNow?.artist?.get(entry.key) ?: 0f
                    liked * 100f + entry.value.sumOf { getSongAffinity(it.id).toDouble() }.toFloat() / entry.value.size
                }

            if (topSinger != null) {
                val singerSongs = topSinger.value.sortedByDescending { rank(it) }
                val singerIds = singerSongs.map { it.id }.toSet()
                val reference = singerSongs.take(5)
                val kindred = allSongs
                    .filter { it.id !in singerIds }
                    .map { candidate -> candidate to (reference.maxOfOrNull { computeSongSimilarity(it, candidate) } ?: 0f) }
                    .filter { it.second > 0.30f }
                    .sortedByDescending { it.second }
                    .take(15)
                    .map { it.first }
                val spotlightPool = (singerSongs.take(30) + kindred).distinctBy { it.id }
                val singerName = topSinger.key.split(' ').joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercase() }
                }

                if (spotlightPool.size >= 4) {
                    generated.add(
                        AiMix(
                            id = "ai_artist_${topSinger.key.hashCode()}",
                            title = context.getString(R.string.ai_mix_artist_radio_title, singerName),
                            subtitle = context.getString(R.string.ai_mix_artist_radio_sub, singerName),
                            category = MixCategory.ARTIST_SPOTLIGHT,
                            songs = generateSmartShuffle(spotlightPool, reference.first()),
                            gradientColors = listOf(Color(0xFFE11D48), Color(0xFFBE123C), Color(0xFF881337)),
                            iconName = "person",
                            description = "A continuous session built around one of your most-loved artists."
                        )
                    )
                }
            }

            // 9. More like the album (movie soundtrack) the user loves most
            val byAlbum = allSongs.filter { featOf(it).album.isNotEmpty() }.groupBy { featOf(it).album }
            val topAlbum = byAlbum.entries
                .filter { it.value.size >= 4 && (tasteNow?.album?.get(it.key) ?: 0f) > 0.05f }
                .maxByOrNull { tasteNow?.album?.get(it.key) ?: 0f }
            if (topAlbum != null) {
                val albumSongs = topAlbum.value.sortedBy { it.trackNumber }
                val albumReference = albumSongs.take(4)
                val similarSongs = allSongs
                    .filter { featOf(it).album != topAlbum.key }
                    .map { candidate -> candidate to (albumReference.maxOfOrNull { computeSongSimilarity(it, candidate) } ?: 0f) }
                    .filter { it.second > 0.32f }
                    .sortedByDescending { it.second }
                    .take(14)
                    .map { it.first }
                val albumPool = (albumSongs + similarSongs).distinctBy { it.id }
                if (albumPool.size >= 6) {
                    val albumName = albumSongs.first().album.trim()
                    generated.add(
                        AiMix(
                            id = "ai_album_${topAlbum.key.hashCode()}",
                            title = "More like $albumName",
                            subtitle = "Sounds like an album you keep coming back to",
                            category = MixCategory.GENRE_DISCOVERY,
                            songs = generateSmartShuffle(albumPool, albumSongs.first()),
                            gradientColors = listOf(Color(0xFF7C3AED), Color(0xFF6D28D9), Color(0xFF4C1D95)),
                            iconName = "album",
                            description = "The album itself, plus songs with the same singers, era and feel."
                        )
                    )
                }
            }

            // 10. Heavy rotation: what has been on repeat over the last days
            val tenDaysAgo = System.currentTimeMillis() - (10L * 24L * 60L * 60L * 1000L)
            val heavyRotation = allSongs.filter { song ->
                val interaction = storage.getInteraction(song.id)
                interaction.lastPlayedTimestamp >= tenDaysAgo && interaction.playCount >= 2 && interaction.consecutiveSkips == 0
            }.sortedByDescending { rank(it) }.take(25)
            if (heavyRotation.size >= 6) {
                generated.add(
                    AiMix(
                        id = "ai_heavy_rotation",
                        title = "Heavy Rotation",
                        subtitle = "What you keep coming back to",
                        category = MixCategory.DAILY_FLOW,
                        songs = generateSmartShuffle(heavyRotation),
                        gradientColors = listOf(Color(0xFF0EA5E9), Color(0xFF2563EB), Color(0xFF4F46E5)),
                        iconName = "repeat",
                        description = "Your most played songs of the last ten days, in a smooth order."
                    )
                )
            }

            _aiMixes.value = generated
            } catch (e: Exception) {
                Log.e("LuneAiEngine", "Error refreshing AI mixes: ${e.message}", e)
            }
        }
    }
}
