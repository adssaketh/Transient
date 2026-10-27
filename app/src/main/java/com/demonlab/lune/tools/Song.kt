package com.demonlab.lune.tools

import android.net.Uri

data class Song(
    val id: Long,
    val albumId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val path: String,
    val dateAdded: Long = 0,
    val albumArtUri: Uri? = null,
    val genre: String? = null,
    val folderName: String,
    val isHiFi: Boolean = false,
    val coverUrl: String? = null,
    val isFavorite: Boolean = false,
    val lyrics: String? = null,
    val format: String = "",
    val bitrate: Int? = null,
    val trackNumber: Int = 0,
    val isHiRes: Boolean = false,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val year: Int = 0,
    val albumArtist: String? = null,
    val composer: String? = null
)

/** Stored as a song's cover when the user deletes its embedded image; no loader can open it, so the placeholder shows. */
const val NO_COVER = "transient-no-cover:"

/** The extra tags of the Edit Information screen. A null track number or year means "keep the file's own value". */
data class ExtraTags(
    val albumArtist: String,
    val composer: String,
    val trackNumber: Int?,
    val year: Int?
)

data class SongArtwork(
    val id: Long,
    val coverUrl: String?,
    val uri: Uri
)

fun Song.toArtwork(): SongArtwork = SongArtwork(id, coverUrl, uri)

