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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class AudioThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = artGate.withPermit { fetchLimited() }

    private suspend fun fetchLimited(): FetchResult? {
        // "Prefer embedded art" (default): the picture stored inside THIS song's file always wins.
        // Android's own thumbnail comes second because it can be an album-level or folder-level image
        // (e.g. a cover.jpg shared by everything in a folder), which made different songs and albums
        // show the same cover. With the setting off, that system thumbnail is tried first instead.
        val own = if (SettingsManager.getInstance(context).preferEmbeddedArt) {
            embeddedArt() ?: systemThumbnail()
        } else {
            systemThumbnail() ?: embeddedArt()
        }
        // A song with no picture of its own borrows the cover of another song from the same album.
        return own ?: albumSiblingArt()
    }

    /**
     * Finds another track with the same album name that has embedded art and uses that picture.
     * The donor track is remembered per album so the other tracks of the album resolve instantly.
     */
    private suspend fun albumSiblingArt(): FetchResult? {
        if (uri.scheme != "content") return null
        val id = runCatching { android.content.ContentUris.parseId(uri) }.getOrNull() ?: return null
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        var albumName: String? = null
        runCatching {
            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(collection, id),
                arrayOf(MediaStore.Audio.Media.ALBUM), null, null, null
            )?.use { c -> if (c.moveToFirst()) albumName = c.getString(0) }
        }
        val album = albumName?.trim()
        if (album.isNullOrEmpty() || album.equals("<unknown>", ignoreCase = true) || album.equals("unknown", ignoreCase = true)) return null
        val key = album.lowercase()
        // Only one track per album searches its siblings; the others wait here and then use the remembered answer.
        return albumLocks.computeIfAbsent(key) { Mutex() }.withLock { siblingArtLocked(key, albumName!!, id, collection) }
    }

    private fun siblingArtLocked(key: String, albumName: String, id: Long, collection: Uri): FetchResult? {
        donorCache[key]?.let { donor ->
            return if (donor == Uri.EMPTY) null else (embeddedViaRetriever(donor) ?: embeddedViaTagger(donor))
        }

        val siblings = mutableListOf<Uri>()
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.ALBUM} = ? AND ${MediaStore.Audio.Media._ID} != ?",
                arrayOf(albumName, id.toString()),
                "${MediaStore.Audio.Media.TRACK} ASC"
            )?.use { c ->
                while (c.moveToNext() && siblings.size < 15) {
                    siblings.add(android.content.ContentUris.withAppendedId(collection, c.getLong(0)))
                }
            }
        }
        for (sibling in siblings) {
            val result = embeddedViaRetriever(sibling) ?: embeddedViaTagger(sibling)
            if (result != null) {
                donorCache[key] = sibling
                return result
            }
        }
        donorCache[key] = Uri.EMPTY
        return null
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

    private fun embeddedViaRetriever(target: Uri = uri): FetchResult? {
        val retriever = MediaMetadataRetriever()
        try {
            if (target.scheme == "file") {
                val path = target.path
                if (path != null && java.io.File(path).exists()) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(context, target)
                }
            } else {
                retriever.setDataSource(context, target)
            }
            val picture = retriever.embeddedPicture
            if (picture != null && picture.isNotEmpty()) return decode(picture)
        } catch (_: Exception) {
            // fall through to the tag reader
        } catch (_: OutOfMemoryError) {
            // a huge file: skip its picture instead of freezing the app
        } finally {
            runCatching { retriever.release() }
        }
        return null
    }

    private fun embeddedViaTagger(target: Uri = uri): FetchResult? {
        try {
            var filePath: String? = if (target.scheme == "file") target.path else null
            if (filePath == null && target.scheme == "content") {
                context.contentResolver.query(target, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idx != -1) filePath = cursor.getString(idx)
                    }
                }
            }
            if (!filePath.isNullOrBlank()) {
                val file = java.io.File(filePath)
                if (file.exists() && file.canRead()) {
                    if (filePath!!.endsWith(".wav", ignoreCase = true)) {
                        // The light reader finds the picture inside the WAV and decodes it straight from the file.
                        val lookup = WavTags.findPicture(file)
                        if (lookup.handled) {
                            val pic = lookup.picture ?: return null
                            return decodeRegion(file, pic.offset, pic.length)
                        }
                    }
                    val audioFile = TagReader.read(file)
                    val artwork = audioFile.tag?.firstArtwork?.binaryData
                    if (artwork != null && artwork.isNotEmpty()) return decode(artwork)
                }
            }
        } catch (_: Exception) {
            // no usable embedded art
        } catch (_: OutOfMemoryError) {
            // a huge tag: skip it
        }
        return null
    }

    /** Decodes a picture stored inside [file], shrinking it while reading, without loading it into memory first. */
    private fun decodeRegion(file: java.io.File, offset: Long, length: Long): FetchResult? {
        val reqW = options.size.width.pxOrElse { 1024 }
        val reqH = options.size.height.pxOrElse { 1024 }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        FileRegionInputStream(file, offset, length).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val bitmap = runCatching {
            FileRegionInputStream(file, offset, length).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull() ?: return null
        return DrawableResult(
            drawable = bitmap.toDrawable(context.resources),
            isSampled = sample > 1,
            dataSource = DataSource.DISK
        )
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

    private companion object {
        /** album name (lower-case) -> a track of that album known to carry artwork, or [Uri.EMPTY] if none does. */
        val donorCache = java.util.concurrent.ConcurrentHashMap<String, Uri>()
        val albumLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

        /** At most two embedded-art lookups run at the same time. */
        val artGate = Semaphore(2)
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
