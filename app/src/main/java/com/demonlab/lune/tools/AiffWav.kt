package com.demonlab.lune.tools

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Detects AIFF / AIFF-C files, which Android's own players cannot decode. */
object AiffSupport {
    private val EXTENSIONS = setOf("aif", "aiff", "aifc")

    /** Duration of an AIFF file read from its own header (MediaStore often reports 0 for AIFF), or 0 if unreadable. */
    fun probeDurationMs(context: Context, uri: Uri): Long {
        val r = AiffWavReader.open(context, uri) ?: return 0L
        return try { r.durationMs } finally { r.close() }
    }

    fun isAiff(song: Song): Boolean {
        val ext = song.path.substringAfterLast('.', "").lowercase()
        return ext in EXTENSIONS || song.format.lowercase() in EXTENSIONS
    }
}

/**
 * Presents an uncompressed AIFF / AIFF-C file as a *virtual WAV file*: a 44-byte header followed by
 * the same samples, byte-swapped to little-endian on the fly. Android's (and ExoPlayer's) WAV support
 * then plays it natively, with seeking, and the audio data is never copied or modified on disk.
 *
 * Supported: 8/16/24/32-bit PCM, "NONE"/"twos" (big-endian) and "sowt" (little-endian) AIFF-C.
 * Anything else (float, A-law, IMA...) makes [open] return null so the caller can fall back.
 */
class AiffWavReader private constructor(
    private val owner: ParcelFileDescriptor,
    private val channel: FileChannel,
    private val dataStart: Long,
    private val dataLength: Long,
    private val bytesPerSample: Int,
    private val swapBytes: Boolean,
    private val flipSignBit: Boolean,
    private val header: ByteArray,
    /** When true the virtual WAV is 16-bit (top 16 bits of each source sample), for players that reject 24/32-bit WAV. */
    private val convert16: Boolean,
    /** Duration of the audio in milliseconds, computed from the AIFF header. */
    val durationMs: Long
) : Closeable {

    /** Length of the audio data as exposed in the virtual WAV. */
    private val outDataLength: Long =
        if (convert16) (dataLength / bytesPerSample) * 2 else dataLength

    /** Length of the virtual WAV file. */
    val size: Long = header.size + outDataLength

    /** Random-access read of the virtual WAV file. Returns -1 at/after the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        if (position < 0 || position >= size) return -1
        var written = 0
        var pos = position
        if (pos < header.size) {
            val n = minOf(length.toLong(), header.size - pos).toInt()
            System.arraycopy(header, pos.toInt(), buffer, offset, n)
            written += n
            pos += n
        }
        if (written < length && pos < size) {
            val dataPos = pos - header.size
            val want = minOf((length - written).toLong(), outDataLength - dataPos).toInt()
            val read = readSamples(dataPos, buffer, offset + written, want)
            if (read <= 0) return if (written > 0) written else -1
            written += read
        }
        return written
    }

    private fun readSamples16(dataPos: Long, out: ByteArray, outOffset: Int, want: Int): Int {
        val bps = bytesPerSample
        val firstSample = dataPos / 2
        val lead = (dataPos % 2).toInt()
        val sampleCount = (lead + want + 1) / 2
        val tmp = ByteArray(sampleCount * bps)
        val buf = ByteBuffer.wrap(tmp)
        var total = 0
        while (buf.hasRemaining()) {
            val n = channel.read(buf, dataStart + firstSample * bps + total)
            if (n <= 0) break
            total += n
        }
        val whole = total / bps
        val conv = ByteArray(whole * 2)
        for (k in 0 until whole) {
            val b = k * bps
            val hi: Int
            val lo: Int
            when {
                bps == 1 -> { hi = tmp[b].toInt(); lo = 0 }           // 8-bit AIFF is signed
                swapBytes -> { hi = tmp[b].toInt(); lo = tmp[b + 1].toInt() and 0xFF }   // big-endian source
                else -> { hi = tmp[b + bps - 1].toInt(); lo = tmp[b + bps - 2].toInt() and 0xFF } // little-endian source
            }
            conv[k * 2] = lo.toByte()
            conv[k * 2 + 1] = hi.toByte()
        }
        val n = minOf(want, (conv.size - lead).coerceAtLeast(0))
        System.arraycopy(conv, lead, out, outOffset, n)
        return n
    }

    private fun readSamples(dataPos: Long, out: ByteArray, outOffset: Int, want: Int): Int {
        if (convert16) return readSamples16(dataPos, out, outOffset, want)
        val bps = bytesPerSample
        if (!swapBytes && !flipSignBit) {
            val n = channel.read(ByteBuffer.wrap(out, outOffset, want), dataStart + dataPos)
            return if (n < 0) 0 else n
        }
        // Work on whole samples so the byte order lines up, then hand back just the part that was asked for.
        val lead = (dataPos % bps).toInt()
        val alignedStart = dataPos - lead
        val alignedLen = ((lead + want + bps - 1) / bps) * bps
        val tmp = ByteArray(alignedLen)
        val buf = ByteBuffer.wrap(tmp)
        var total = 0
        while (buf.hasRemaining()) {
            val n = channel.read(buf, dataStart + alignedStart + total)
            if (n <= 0) break
            total += n
        }
        val whole = total - (total % bps)
        if (swapBytes) {
            var i = 0
            while (i < whole) {
                for (j in 0 until bps / 2) {
                    val a = tmp[i + j]
                    tmp[i + j] = tmp[i + bps - 1 - j]
                    tmp[i + bps - 1 - j] = a
                }
                i += bps
            }
        }
        if (flipSignBit) {
            // 8-bit AIFF is signed, 8-bit WAV is unsigned.
            for (i in 0 until whole) tmp[i] = (tmp[i].toInt() xor 0x80).toByte()
        }
        val n = minOf(want, (whole - lead).coerceAtLeast(0))
        System.arraycopy(tmp, lead, out, outOffset, n)
        return n
    }

    override fun close() {
        try { owner.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AiffWavReader"

        fun open(context: Context, uri: Uri, convertTo16Bit: Boolean = false): AiffWavReader? {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open $uri: ${e.message}")
                null
            } ?: return null
            return try {
                parse(pfd, convertTo16Bit) ?: run { pfd.close(); null }
            } catch (e: Exception) {
                Log.w(TAG, "Not a usable AIFF ($uri): ${e.message}")
                try { pfd.close() } catch (_: Exception) {}
                null
            }
        }

        private fun parse(pfd: ParcelFileDescriptor, convertTo16Bit: Boolean): AiffWavReader? {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val fileSize = channel.size()
            val head = readFully(channel, 0, 12) ?: return null
            if (String(head, 0, 4, Charsets.US_ASCII) != "FORM") return null
            val form = String(head, 8, 4, Charsets.US_ASCII)
            val isAifc = form == "AIFC"
            if (form != "AIFF" && !isAifc) return null

            var channels = 0
            var frames = 0L
            var bits = 0
            var rate = 0
            var littleEndian = false
            var haveComm = false
            var ssndStart = -1L
            var ssndLen = 0L

            var pos = 12L
            while (pos + 8 <= fileSize) {
                val ch = readFully(channel, pos, 8) ?: break
                val id = String(ch, 0, 4, Charsets.US_ASCII)
                val sz = ByteBuffer.wrap(ch, 4, 4).int.toLong() and 0xFFFFFFFFL
                val body = pos + 8
                when (id) {
                    "COMM" -> {
                        val c = readFully(channel, body, minOf(sz, 64L).toInt()) ?: return null
                        if (c.size < 18) return null
                        val bb = ByteBuffer.wrap(c)
                        channels = bb.getShort(0).toInt() and 0xFFFF
                        frames = bb.getInt(2).toLong() and 0xFFFFFFFFL
                        bits = bb.getShort(6).toInt() and 0xFFFF
                        rate = extendedToInt(c, 8)
                        if (isAifc) {
                            if (c.size < 22) return null
                            when (String(c, 18, 4, Charsets.US_ASCII)) {
                                "NONE", "twos" -> littleEndian = false
                                "sowt" -> littleEndian = true
                                else -> return null
                            }
                        }
                        haveComm = true
                    }
                    "SSND" -> {
                        val s = readFully(channel, body, 8) ?: return null
                        val offset = ByteBuffer.wrap(s).int.toLong() and 0xFFFFFFFFL
                        ssndStart = body + 8 + offset
                        ssndLen = (sz - 8 - offset).coerceAtLeast(0L)
                    }
                }
                pos = body + sz + (sz and 1L)
            }

            if (!haveComm || ssndStart < 0 || channels <= 0 || rate <= 0 || bits <= 0) return null
            val bps = (bits + 7) / 8
            if (bps !in 1..4) return null
            val frameBytes = channels.toLong() * bps
            var dataLen = minOf(ssndLen, fileSize - ssndStart)
            if (frames > 0) dataLen = minOf(dataLen, frames * frameBytes)
            dataLen = minOf(dataLen, 0xFFFFFFFFL - 64L)           // WAV sizes are 32-bit
            dataLen -= dataLen % frameBytes
            if (dataLen <= 0) return null

            val convert16 = convertTo16Bit && bps != 2
            val outBps = if (convert16) 2 else bps
            val outLen = if (convert16) (dataLen / bps) * 2 else dataLen
            val durationMs = dataLen * 1000L / (rate.toLong() * frameBytes)

            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII)); h.putInt((36 + outLen).toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII)); h.putInt(16)
            h.putShort(1)                                          // PCM
            h.putShort(channels.toShort()); h.putInt(rate)
            h.putInt(rate * channels * outBps)
            h.putShort((channels * outBps).toShort()); h.putShort((outBps * 8).toShort())
            h.put("data".toByteArray(Charsets.US_ASCII)); h.putInt(outLen.toInt())

            return AiffWavReader(
                owner = pfd,
                channel = channel,
                dataStart = ssndStart,
                dataLength = dataLen,
                bytesPerSample = bps,
                swapBytes = !littleEndian && bps > 1,
                flipSignBit = bps == 1,
                header = h.array(),
                convert16 = convert16,
                durationMs = durationMs
            )
        }

        private fun readFully(ch: FileChannel, position: Long, length: Int): ByteArray? {
            val arr = ByteArray(length)
            val bb = ByteBuffer.wrap(arr)
            var p = position
            while (bb.hasRemaining()) {
                val n = ch.read(bb, p)
                if (n <= 0) return null
                p += n
            }
            return arr
        }

        /** AIFF stores the sample rate as an 80-bit IEEE extended float. */
        private fun extendedToInt(b: ByteArray, off: Int): Int {
            val exponent = ((b[off].toInt() and 0x7F) shl 8) or (b[off + 1].toInt() and 0xFF)
            var mantissa = 0L
            for (i in 0 until 8) mantissa = (mantissa shl 8) or (b[off + 2 + i].toLong() and 0xFF)
            val m = if (mantissa >= 0) mantissa.toDouble() else mantissa.toDouble() + 18446744073709551616.0
            return Math.round(java.lang.Math.scalb(m, exponent - 16383 - 63)).toInt()
        }
    }
}

/** Lets android.media.MediaPlayer play an AIFF file through [AiffWavReader]. */
class AiffMediaDataSource(private val reader: AiffWavReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        reader.readAt(position, buffer, offset, size)

    override fun getSize(): Long = reader.size

    override fun close() {
        reader.close()
    }
}
