package com.demonlab.lune.tools.usb

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.demonlab.lune.tools.AiffWavReader
import java.io.IOException

/**
 * ExoPlayer [androidx.media3.datasource.DataSource] that serves an AIFF file as a virtual WAV stream
 * (see [AiffWavReader]); ExoPlayer's WAV extractor then handles it like any other WAV.
 */
@OptIn(UnstableApi::class)
class AiffWavDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: AiffWavReader? = null
    private var currentUri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = AiffWavReader.open(context, dataSpec.uri)
            ?: throw IOException("Unsupported or unreadable AIFF file")
        reader = r
        if (dataSpec.position > r.size) throw IOException("Position out of range")
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        val n = r.readAt(position, buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
