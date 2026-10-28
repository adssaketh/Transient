package com.demonlab.lune.tools

import android.content.Context
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The seed colour of a song's cover (it drives the app's colour theme), worked out once per song and remembered.
 *
 * The theme used to wait until a song had started, then decode the cover again from the file and only then change colours,
 * which showed up as a delay of up to ~2 s after the cover had already changed. Now the colour can be prepared ahead of
 * time (while the disc rolls in), and the cover is read through the image cache (INEXACT: any cached copy at least as big
 * as needed is reused, such as the one the song list already shows).
 */
object AlbumArtColors {
    private val cache = ConcurrentHashMap<Long, Int>()

    fun cached(songId: Long): Int? = cache[songId]

    suspend fun seedFor(context: Context, song: Song): Int? {
        cache[song.id]?.let { return it }
        val color = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(song.coverUrl ?: song.albumArtUri ?: song.uri)
                    .size(128, 128)
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
                val result = Coil.imageLoader(context).execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
                bitmap?.let { ImageAnalyzer.dominantColor(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (color != null) cache[song.id] = color
        return color
    }
}
