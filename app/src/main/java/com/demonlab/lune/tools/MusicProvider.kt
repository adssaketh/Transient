package com.demonlab.lune.tools

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.demonlab.lune.data.MusicDatabase
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UriTypeAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value.toString())
        }
    }

    override fun read(reader: JsonReader): Uri? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return reader.nextString().toUri() // Modern KTX string-to-Uri converter
    }
}

class MusicProvider(private val context: Context) {
    private val settingsManager = SettingsManager.getInstance(context)
    private val database = MusicDatabase.getDatabase(context)

    private val cacheFile = File(context.filesDir, "songs_cache.json")
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
        .disableHtmlEscaping()
        .create()

    fun getCachedSongs(): List<Song> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            val json = cacheFile.readText()
            val type = object : TypeToken<List<Song>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveToCache(songs: List<Song>) {
        try {
            val json = gson.toJson(songs)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSongInCache(updatedSong: Song) {
        val songs = getCachedSongs().toMutableList()
        val idx = songs.indexOfFirst { it.id == updatedSong.id }
        if (idx >= 0) {
            songs[idx] = updatedSong
            saveToCache(songs)
        } else {
            songs.add(updatedSong)
            saveToCache(songs)
        }
    }

    private fun hasReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncSongs(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        HiFiFileInfo.beginScan()
        val songList = mutableListOf<Song>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projectionList = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.TRACK,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.Audio.Media.GENRE
            } else {
                MediaStore.Audio.Media.ARTIST
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.BITRATE)
        }
        projectionList.add(MediaStore.Audio.Media.YEAR)
        projectionList.add(MediaStore.Audio.Media.COMPOSER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            projectionList.add(MediaStore.Audio.Media.ALBUM_ARTIST)
        }
        val projection = projectionList.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val overrides = database.songOverrideDao().getAllOverrides().associateBy { it.songId }

        context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearColumn = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val genreColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else -1
            val bitrateColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
            } else -1
            val composerColumn = cursor.getColumnIndex(MediaStore.Audio.Media.COMPOSER)
            val albumArtistColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                var title = cursor.getString(titleColumn)
                var artist = cursor.getString(artistColumn)
                var album = cursor.getString(albumColumn)
                var duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)
                val data = cursor.getString(dataColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                var trackNumber = if (trackColumn != -1) cursor.getInt(trackColumn) else 0
                var year = if (yearColumn != -1) cursor.getInt(yearColumn).coerceAtLeast(0) else 0
                var genre = if (genreColumn != -1) cursor.getString(genreColumn) else null
                var albumArtist: String? = if (albumArtistColumn != -1) cursor.getString(albumArtistColumn) else null
                var composer: String? = if (composerColumn != -1) cursor.getString(composerColumn) else null

                val override = overrides[id]
                var coverUrl: String? = null
                var isFavorite = false

                if (override != null) {
                    override.title?.let { if (it.isNotBlank()) title = it }
                    override.artist?.let { if (it.isNotBlank()) artist = it }
                    override.album?.let { if (it.isNotBlank()) album = it }
                    override.genre?.let { if (it.isNotBlank()) genre = it }
                    override.albumArtist?.let { if (it.isNotBlank()) albumArtist = it }
                    override.composer?.let { if (it.isNotBlank()) composer = it }
                    override.trackNumber?.let { if (it > 0) trackNumber = it }
                    override.year?.let { if (it > 0) year = it }
                    coverUrl = override.coverUri
                    isFavorite = override.isFavorite
                }

                albumArtist = albumArtist?.let { CharsetUtils.sanitizeText(it) }
                    ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                composer = composer?.let { CharsetUtils.sanitizeText(it) }
                    ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }

                title = CharsetUtils.sanitizeText(title)
                artist = CharsetUtils.sanitizeText(artist)
                album = CharsetUtils.sanitizeText(album)

                val folderName = data.substringBeforeLast("/").substringAfterLast("/")
                val extension = data.substringAfterLast(".").lowercase()
                if (duration <= 0L && (extension == "aif" || extension == "aiff" || extension == "aifc")) {
                    // MediaStore cannot read AIFF durations (shows 0:00): take it from the file's own header.
                    duration = AiffSupport.probeDurationMs(context, ContentUris.withAppendedId(collection, id))
                }
                val isHiFiFile = extension == "flac" || extension == "wav" || extension == "alac" || extension == "aiff" || extension == "aif" || extension == "aifc" || extension == "dsf" || extension == "dff"
                val isHiFi = settingsManager.enableHiFi && isHiFiFile
                val format = when (extension) {
                    "mp3" -> "MP3"
                    "flac" -> "FLAC"
                    "wav" -> "WAV"
                    "aac", "m4a" -> "AAC"
                    "ogg" -> "OGG"
                    "opus" -> "OPUS"
                    "wma" -> "WMA"
                    "alac" -> "ALAC"
                    "dsf", "dff" -> "DSD"
                    else -> extension.uppercase()
                }
                val file = File(data)
                
                val mediaStoreBitrate = if (bitrateColumn != -1) {
                    val value = cursor.getInt(bitrateColumn)
                    if (value > 0) value else null
                } else null

                val calculatedBitrate = if (duration > 0 && file.exists()) {
                    ((file.length() * 8) / (duration / 1000f)).toInt()
                } else null

                val bitrate = mediaStoreBitrate ?: calculatedBitrate

                var sampleRate: Int? = null
                var bitDepth: Int? = null
                var isHiRes = false
                if (isHiFiFile && file.exists()) {
                    try {
                        // Cached per file and read lightly (no cover picture is loaded into memory); see HiFiFileInfo.
                        val info = HiFiFileInfo.get(context, file, extension)
                        sampleRate = info.sampleRate
                        bitDepth = info.bitsPerSample
                        if (bitDepth >= 24 || sampleRate > 48000 || extension == "dsf" || extension == "dff") {
                            isHiRes = true
                        }

                        // Extract tags for formats where Android MediaStore does not parse metadata (e.g. WAV, DSF, DFF)
                        // or where MediaStore tags were missing/fallback unknown values
                        if (override == null) {
                            val tagTitle = info.title
                            val tagArtist = info.artist
                            val tagAlbum = info.album
                            val tagGenre = info.genre
                            val tagTrack = info.track
                            val tagAlbumArtist = info.albumArtist
                            val tagComposer = info.composer
                            if (!tagAlbumArtist.isNullOrBlank() && albumArtist.isNullOrBlank()) {
                                albumArtist = CharsetUtils.sanitizeText(tagAlbumArtist)
                            }
                            if (!tagComposer.isNullOrBlank() && composer.isNullOrBlank()) {
                                composer = CharsetUtils.sanitizeText(tagComposer)
                            }

                            if (!tagTitle.isNullOrBlank()) {
                                title = CharsetUtils.sanitizeText(tagTitle)
                            }
                            if (!tagArtist.isNullOrBlank() && (artist.isBlank() || artist.equals("<unknown>", ignoreCase = true) || extension == "wav")) {
                                artist = CharsetUtils.sanitizeText(tagArtist)
                            }
                            if (!tagAlbum.isNullOrBlank() && (album.isBlank() || album.equals("<unknown>", ignoreCase = true) || extension == "wav")) {
                                album = CharsetUtils.sanitizeText(tagAlbum)
                            }
                            if (!tagGenre.isNullOrBlank() && (genre.isNullOrBlank() || extension == "wav")) {
                                genre = CharsetUtils.sanitizeText(tagGenre)
                            }
                            if (!tagTrack.isNullOrBlank() && (trackNumber == 0 || extension == "wav")) {
                                trackNumber = tagTrack.substringBefore("/").toIntOrNull() ?: trackNumber
                            }
                        }
                    } catch (_: Exception) {
                        if (bitrate != null && bitrate >= 2304000) {
                            isHiRes = true
                        }
                    }
                }

                val contentUri: Uri = ContentUris.withAppendedId(collection, id)

                // Use the song's specific contentUri as albumArtUri so AudioThumbnailFetcher extracts
                // the per-song embedded picture rather than leaking shared MediaStore albumart (Fixes Issue #106)
                val albumArtUri = contentUri

                songList.add(
                    Song(
                        id, albumId, title, artist, album, duration, contentUri, data,
                        dateAdded, albumArtUri, genre, folderName, isHiFi, coverUrl, isFavorite, null,
                        format, bitrate, trackNumber, isHiRes, sampleRate, bitDepth, year = year,
                        albumArtist = albumArtist, composer = composer
                    )
                )
            }
        }
        HiFiFileInfo.flush(context)
        saveToCache(songList)
        songList
    }

    suspend fun refreshLibrary(): List<Song> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()

        val audioExtensions = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac")

        // Collect paths: known songs from cache + common music directories
        val pathsToScan = mutableSetOf<String>()
        getCachedSongs().forEach { pathsToScan.add(it.path) }

        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        if (musicDir.exists()) pathsToScan.add(musicDir.absolutePath)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir.exists()) pathsToScan.add(downloadsDir.absolutePath)

        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (documentsDir.exists()) pathsToScan.add(documentsDir.absolutePath)

        val audioFolderUriStr = settingsManager.musicFolderUri
        if (!audioFolderUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(audioFolderUriStr)
                val pathSegment = uri.lastPathSegment?.substringAfterLast(":")
                if (!pathSegment.isNullOrBlank()) {
                    val customFolder = File(Environment.getExternalStorageDirectory(), pathSegment)
                    if (customFolder.exists()) pathsToScan.add(customFolder.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (pathsToScan.isNotEmpty()) {
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(
                context,
                pathsToScan.toTypedArray(),
                null
            ) { _, _ -> latch.countDown() }
            latch.await(60, TimeUnit.SECONDS)
        }

        syncSongs()
    }
}