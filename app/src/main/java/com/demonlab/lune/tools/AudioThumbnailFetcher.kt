package com.demonlab.lune.tools

import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.pxOrElse

class AudioThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // "Prefer embedded art" (default): the picture stored inside THIS song's file always wins.
        // Android's own thumbnail comes second because it can be an album-level or folder-level image
        // (e.g. a cover.jpg shared by everything in a folder), which made different songs and albums
        // show the same cover. With the setting off, that system thumbnail is tried first instead.
        return if (SettingsManager.getInstance(context).preferEmbeddedArt) {
            embeddedArt() ?: systemThumbnail()
        } else {
            systemThumbnail() ?: embeddedArt()
        }
    }

    /** Android's thumbnail for the file (fast and system-cached, but may come from the folder / album). */
    private fun systemThumbnail(): FetchResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.scheme != "content") return null
        val thumbnail = runCatching {
            val width = options.size.width.pxOrElse { 512 }
            val height = options.size.height.pxOrElse { 512 }
            context.contentResolver.loadThumbnail(uri, android.util.Size(width, height), null)
        }.getOrNull() ?: return null
        return DrawableResult(
            drawable = thumbnail.toDrawable(context.resources),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    /** The picture embedded in the file itself: platform retriever first, then jaudiotagger (WAV/AIFF/DSF/FLAC...). */
    private fun embeddedArt(): FetchResult? {
        embeddedViaRetriever()?.let { return it }
        return embeddedViaTagger()
    }

    private fun embeddedViaRetriever(): FetchResult? {
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null && java.io.File(path).exists()) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, uri)
                }
            } else {
                retriever.setDataSource(context, uri)
            }
            val picture = retriever.embeddedPicture
            if (picture != null && picture.isNotEmpty()) return decode(picture)
        } catch (_: Exception) {
            // fall through to the tag reader
        } finally {
            runCatching { retriever.release() }
        }
        return null
    }

    private fun embeddedViaTagger(): FetchResult? {
        try {
            var filePath: String? = if (uri.scheme == "file") uri.path else null
            if (filePath == null && uri.scheme == "content") {
                context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx != -1) filePath = cursor.getString(idx)
                    }
                }
            }
            if (!filePath.isNullOrBlank()) {
                val file = java.io.File(filePath)
                if (file.exists() && file.canRead()) {
                    val audioFile = org.jaudiotagger.audio.AudioFileIO.read(file)
                    val artwork = audioFile.tag?.firstArtwork?.binaryData
                    if (artwork != null && artwork.isNotEmpty()) return decode(artwork)
                }
            }
        } catch (_: Exception) {
            // no usable embedded art
        }
        return null
    }

    /** Decodes [bytes], shrinking to roughly the requested size so album grids stay light on memory. */
    private fun decode(bytes: ByteArray): FetchResult? {
        val reqW = options.size.width.pxOrElse { 0 }
        val reqH = options.size.height.pxOrElse { 0 }
        var sample = 1
        if (reqW > 0 && reqH > 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        }
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        return DrawableResult(
            drawable = bitmap.toDrawable(context.resources),
            isSampled = sample > 1,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme == "content" || scheme == "file") {
                val str = data.toString().lowercase()
                val isAudio = str.contains("audio") ||
                        str.endsWith(".mp3") ||
                        str.endsWith(".flac") ||
                        str.endsWith(".wav") ||
                        str.endsWith(".ogg") ||
                        str.endsWith(".m4a") ||
                        str.endsWith(".opus") ||
                        str.endsWith(".aac") ||
                        str.endsWith(".aiff") ||
                        str.endsWith(".aif") ||
                        str.endsWith(".dsf") ||
                        str.endsWith(".dff") ||
                        str.endsWith(".wv") ||
                        str.endsWith(".ape") ||
                        runCatching { context.contentResolver.getType(data)?.startsWith("audio/") == true }.getOrDefault(false)
                if (isAudio) {
                    return AudioThumbnailFetcher(data, options, context)
                }
            }
            return null
        }
    }
}
