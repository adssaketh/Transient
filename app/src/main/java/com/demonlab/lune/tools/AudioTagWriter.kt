package com.demonlab.lune.tools

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** What the Edit Information screen wants written into the audio file itself. */
data class FileTagEdit(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val albumArtist: String,
    val composer: String,
    val trackNumber: Int?,
    val year: Int?,
    /** null = keep the picture, NO_COVER = remove it, anything else = a picture the user picked. */
    val coverUri: Uri?
)

/**
 * Writes tags (and the embedded picture) into the audio file, not only into the app's own database.
 *
 * Android does not let an app change another app's file directly, so the steps are: (1) ask the user once through the system
 * dialog, (2) copy the song to a private temporary file, (3) change the tags there with jaudiotagger, (4) check that the
 * changed copy can be read back, (5) keep a backup of the original, and only then (6) write the copy over the song. If step 6
 * fails half way, the backup is written back so the song is not left damaged.
 */
object AudioTagWriter {
    private const val TAG = "AudioTagWriter"

    /** True when [edit] differs from what [song] shows now, i.e. there is something to write to the file. */
    fun hasChanges(song: Song, edit: FileTagEdit): Boolean {
        val cover = edit.coverUri
        return edit.title != song.title ||
            edit.artist != song.artist ||
            edit.album != song.album ||
            edit.genre != (song.genre ?: "") ||
            edit.albumArtist != (song.albumArtist ?: "") ||
            edit.composer != (song.composer ?: "") ||
            (edit.trackNumber != null && edit.trackNumber != song.trackNumber) ||
            (edit.year != null && edit.year != song.year) ||
            cover != null
    }

    /**
     * The system dialog that lets the user approve changing this song, or null when no approval is needed (already allowed,
     * or an Android version that does not ask). Run it off the main thread.
     */
    suspend fun approvalRequest(context: Context, song: Song): IntentSender? = withContext(Dispatchers.IO) {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val granted = context.checkUriPermission(
                        song.uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) null
                    else MediaStore.createWriteRequest(context.contentResolver, listOf(song.uri)).intentSender
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    try {
                        context.contentResolver.openFileDescriptor(song.uri, "rw")?.close()
                        null
                    } catch (e: RecoverableSecurityException) {
                        e.userAction.actionIntent.intentSender
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "No approval request possible: ${e.message}")
            null
        }
    }

    /** Writes the changes into the file. Returns true only when the song itself was changed and verified. */
    suspend fun write(context: Context, song: Song, edit: FileTagEdit): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = song.path.substringAfterLast('.', "").lowercase().ifEmpty { "mp3" }
        val dir = File(context.cacheDir, "tag_edit").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val work = File(dir, "work_${song.id}.$ext")
        val backup = File(dir, "backup_${song.id}.$ext")
        var overwriteStarted = false
        try {
            resolver.openInputStream(song.uri)?.use { input -> work.outputStream().use { input.copyTo(it) } }
                ?: return@withContext false
            work.copyTo(backup, overwrite = true)

            val audio = AudioFileIO.read(work)
            val tag: Tag = audio.tagOrCreateAndSetDefault

            fun text(key: FieldKey, new: String, old: String) {
                if (new == old) return
                if (new.isBlank()) tag.deleteField(key) else tag.setField(key, new)
            }
            text(FieldKey.TITLE, edit.title, song.title)
            text(FieldKey.ARTIST, edit.artist, song.artist)
            text(FieldKey.ALBUM, edit.album, song.album)
            text(FieldKey.GENRE, edit.genre, song.genre ?: "")
            text(FieldKey.ALBUM_ARTIST, edit.albumArtist, song.albumArtist ?: "")
            text(FieldKey.COMPOSER, edit.composer, song.composer ?: "")
            edit.trackNumber?.let { if (it != song.trackNumber) tag.setField(FieldKey.TRACK, it.toString()) }
            edit.year?.let { if (it != song.year) tag.setField(FieldKey.YEAR, it.toString()) }

            val cover = edit.coverUri
            when {
                cover == null -> Unit
                cover.toString() == NO_COVER -> tag.deleteArtworkField()
                else -> {
                    val jpeg = readAsJpeg(context, cover) ?: return@withContext false
                    tag.deleteArtworkField()
                    if (tag is FlacTag) {
                        // The library's Artwork class needs javax.imageio, which Android does not have, so a FLAC picture block
                        // is built by hand (a front cover, with its size read from the picture).
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                        val picture = MetadataBlockDataPicture(
                            jpeg, 3, "image/jpeg", "", maxOf(bounds.outWidth, 0), maxOf(bounds.outHeight, 0), 24, 0
                        )
                        tag.setField(picture)
                    } else {
                        val art = ArtworkFactory.getNew()
                        art.binaryData = jpeg
                        art.mimeType = "image/jpeg"
                        art.pictureType = 3 // front cover
                        tag.setField(art)
                    }
                }
            }
            audio.commit()

            // The changed copy must read back with the new values before the real song is touched.
            val check = AudioFileIO.read(work).tag ?: return@withContext false
            if (edit.title != song.title && check.getFirst(FieldKey.TITLE) != edit.title) return@withContext false

            overwriteStarted = true
            resolver.openOutputStream(song.uri, "wt")?.use { out -> work.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Could not open the song for writing")
            overwriteStarted = false

            MediaScannerConnection.scanFile(context, arrayOf(song.path), null, null)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not write tags: ${e.message}", e)
            DiagnosticLogger.error("Tag writer", "Could not write tags into ${song.path.substringAfterLast('/')}", e)
            if (overwriteStarted) restore(context, song, backup)
            false
        } finally {
            work.delete()
            backup.delete()
        }
    }

    private fun restore(context: Context, song: Song, backup: File) {
        try {
            context.contentResolver.openOutputStream(song.uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not put the original song back: ${e.message}")
        }
    }

    /** The picked picture as a JPEG of at most 1000 px, so the file does not grow much. */
    private fun readAsJpeg(context: Context, uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2000 || bounds.outHeight / sample > 2000) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > 1000) {
                val f = 1000f / longest
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt().coerceAtLeast(1), (bitmap.height * f).toInt().coerceAtLeast(1), true)
            } else bitmap
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        } catch (e: Throwable) {
            Log.w(TAG, "Could not read the picked picture: ${e.message}")
            null
        }
    }
}
