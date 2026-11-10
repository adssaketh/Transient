package com.demonlab.lune.tools

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a spectrogram (short-time Fourier transform) of an audio file.
 *
 * The file is decoded once with Android's own decoders (MediaExtractor + MediaCodec; AIFF goes through
 * [AiffWavReader]) and streamed through a Hann-windowed FFT, so memory use does not depend on the length of the
 * track. One "plane" is produced for the mix of all channels and one for every individual channel (up to 8).
 *
 * Levels are stored as one byte per cell: 0 = [FLOOR_DB] dBFS, 255 = 0 dBFS (a full-scale sine reads 0 dB).
 */
object SpectrogramAnalyzer {

    const val FLOOR_DB = -120f
    private const val TARGET_COLUMNS = 1600
    private const val MAX_CHANNEL_PLANES = 8

    class Result(
        val sampleRate: Int,
        val channels: Int,
        val mime: String,
        val durationSec: Float,
        val fftSize: Int,
        val columns: Int,
        /** Frequency rows per column: fftSize / 2, covering 0 Hz .. sampleRate / 2. */
        val bins: Int,
        /** planes[0] = mix of all channels; planes[1..n] = individual channels. Index = column * bins + bin. */
        val planes: Array<ByteArray>
    ) {
        val hasChannelPlanes: Boolean get() = planes.size > 1
    }

    class UnsupportedAudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun isAiff(path: String, format: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        val fmt = format.lowercase()
        return ext in setOf("aif", "aiff", "aifc") || fmt in setOf("aif", "aiff", "aifc")
    }

    /**
     * @param onProgress called with 0..1 from the calling thread
     * @param isActive polled between buffers; return false to abandon the analysis
     */
    fun analyze(
        context: Context,
        uri: Uri,
        path: String,
        format: String,
        durationHintMs: Long,
        fftSize: Int,
        onProgress: (Float) -> Unit,
        isActive: () -> Boolean
    ): Result? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var aiffSource: MediaDataSource? = null
        try {
            try {
                val aiffReader = if (isAiff(path, format)) AiffWavReader.open(context, uri) else null
                if (aiffReader != null) {
                    aiffSource = AiffMediaDataSource(aiffReader)
                    extractor.setDataSource(aiffSource)
                } else {
                    extractor.setDataSource(context, uri, null)
                }
            } catch (e: Exception) {
                throw UnsupportedAudioException("The file could not be opened", e)
            }

            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    inFormat = f
                    break
                }
            }
            if (track < 0 || inFormat == null) throw UnsupportedAudioException("No audio track found")
            extractor.selectTrack(track)

            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            var sampleRate = if (inFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (inFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            val durationUs = when {
                inFormat.containsKey(MediaFormat.KEY_DURATION) -> inFormat.getLong(MediaFormat.KEY_DURATION)
                durationHintMs > 0 -> durationHintMs * 1000L
                else -> 0L
            }

            // Float output only from real decoders. The raw/PCM path (WAV, AIFF) is a pass-through:
            // forcing float there makes it report float while the bytes are still integer PCM.
            if (mime != "audio/raw") {
                try {
                    inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                } catch (_: Exception) {
                }
            }

            val dec = try {
                MediaCodec.createDecoderByType(mime).also {
                    it.configure(inFormat, null, null, 0)
                    it.start()
                }
            } catch (e: Exception) {
                throw UnsupportedAudioException("This audio format can't be decoded on this device", e)
            }
            codec = dec

            var bank: StftBank? = null
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            fun readOutputFormat(f: MediaFormat) {
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                pcmEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                else AudioFormat.ENCODING_PCM_16BIT
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleSpins = 0
            var lastProgress = -1f
            while (!outputDone) {
                if (!isActive()) return null

                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        readOutputFormat(dec.outputFormat)
                        if (bank != null) throw UnsupportedAudioException("The audio format changed during decoding")
                    }
                    outIdx >= 0 -> {
                        idleSpins = 0
                        if (info.size > 0) {
                            if (bank == null) {
                                readOutputFormat(dec.outputFormat)
                                val est = if (durationUs > 0) durationUs / 1_000_000.0 * sampleRate else 0.0
                                bank = StftBank(sampleRate, channels, fftSize, est)
                            }
                            val out = dec.getOutputBuffer(outIdx)
                            if (out != null) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                out.order(ByteOrder.LITTLE_ENDIAN)
                                bank.consume(out, pcmEncoding)
                            }
                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress >= 0.01f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> {
                        // INFO_TRY_AGAIN_LATER: if the input is finished and nothing comes out for a long while, stop.
                        if (inputDone && ++idleSpins > 300) outputDone = true
                    }
                }
            }

            val b = bank ?: throw UnsupportedAudioException("The file contains no decodable audio")
            return b.finish("$mime enc=$pcmEncoding")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { aiffSource?.close() } catch (_: Exception) {}
        }
    }

    /** Collects samples into one windowed FFT per output column, for the mix and for each channel. */
    private class StftBank(
        private val sampleRate: Int,
        private val channels: Int,
        private val fftSize: Int,
        estimatedFrames: Double
    ) {
        private val bins = fftSize / 2
        private val channelPlanes = if (channels > 1) min(channels, MAX_CHANNEL_PLANES) else 0
        private val planeCount = 1 + channelPlanes
        private val rings = Array(planeCount) { FloatArray(fftSize) }
        private var writeIndex = 0
        private var frames = 0L

        private val hop: Int = if (estimatedFrames > fftSize) {
            max(32, ((estimatedFrames - fftSize) / (TARGET_COLUMNS - 1)).toInt())
        } else {
            max(32, fftSize / 8)
        }
        private var nextTrigger = fftSize.toLong()

        private val columns = Array(planeCount) { ArrayList<ByteArray>(TARGET_COLUMNS + 16) }

        private val window = FloatArray(fftSize)
        private val windowSum: Float
        private val re = FloatArray(fftSize)
        private val im = FloatArray(fftSize)
        private val fft = Fft(fftSize)
        private val frame = FloatArray(max(channels, 1))

        init {
            var sum = 0.0
            for (i in 0 until fftSize) {
                val w = 0.5 - 0.5 * cos(2.0 * PI * i / fftSize)
                window[i] = w.toFloat()
                sum += w
            }
            windowSum = sum.toFloat()
        }

        fun consume(buf: ByteBuffer, encoding: Int) {
            val bytesPerSample = when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT, 22 /* ENCODING_PCM_32BIT */ -> 4
                21 /* ENCODING_PCM_24BIT_PACKED */ -> 3
                else -> 2
            }
            val ch = max(channels, 1)
            val frameBytes = ch * bytesPerSample
            var pos = buf.position()
            val end = buf.limit()
            while (pos + frameBytes <= end) {
                for (c in 0 until ch) {
                    frame[c] = when (encoding) {
                        AudioFormat.ENCODING_PCM_8BIT -> ((buf.get(pos).toInt() and 0xFF) - 128) / 128f
                        AudioFormat.ENCODING_PCM_FLOAT -> buf.getFloat(pos)
                        22 -> buf.getInt(pos) / 2147483648f
                        21 -> {
                            val v = (buf.get(pos).toInt() and 0xFF) or
                                ((buf.get(pos + 1).toInt() and 0xFF) shl 8) or
                                (buf.get(pos + 2).toInt() shl 16)
                            v / 8388608f
                        }
                        else -> buf.getShort(pos) / 32768f
                    }
                    pos += bytesPerSample
                }
                pushFrame(ch)
            }
            buf.position(pos)
        }

        private fun pushFrame(ch: Int) {
            var mix = 0f
            for (c in 0 until ch) mix += frame[c]
            rings[0][writeIndex] = mix / ch
            for (c in 0 until channelPlanes) rings[1 + c][writeIndex] = frame[c]
            writeIndex++
            if (writeIndex == fftSize) writeIndex = 0
            frames++
            if (frames >= nextTrigger) {
                nextTrigger += hop
                for (p in 0 until planeCount) columns[p].add(transform(rings[p]))
            }
        }

        private fun transform(ring: FloatArray): ByteArray {
            // Oldest sample sits at writeIndex.
            var idx = writeIndex
            for (i in 0 until fftSize) {
                re[i] = ring[idx] * window[i]
                im[i] = 0f
                idx++
                if (idx == fftSize) idx = 0
            }
            fft.transform(re, im)
            val out = ByteArray(bins)
            val scale = 2f / windowSum
            for (k in 0 until bins) {
                val rawMag = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
                val mag = if (rawMag.isNaN()) 0f else rawMag
                val db = 20f * log10(max(mag, 1e-9f))
                val q = ((db - FLOOR_DB) / -FLOOR_DB * 255f).toInt().coerceIn(0, 255)
                out[k] = q.toByte()
            }
            return out
        }

        fun finish(mime: String): Result {
            // A very short file never reaches the first trigger: analyse whatever there is, zero-padded.
            if (columns[0].isEmpty() && frames > 0) {
                for (p in 0 until planeCount) columns[p].add(transform(rings[p]))
            }
            val cols = columns[0].size
            if (cols == 0) throw UnsupportedAudioException("The file contains no decodable audio")
            val planes = Array(planeCount) { p ->
                val data = ByteArray(cols * bins)
                for (c in 0 until cols) System.arraycopy(columns[p][c], 0, data, c * bins, bins)
                data
            }
            return Result(
                sampleRate = sampleRate,
                channels = channels,
                mime = mime,
                durationSec = frames.toFloat() / sampleRate,
                fftSize = fftSize,
                columns = cols,
                bins = bins,
                planes = planes
            )
        }
    }

    /** In-place iterative radix-2 FFT. */
    private class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2)
        private val sinT = FloatArray(n / 2)
        private val rev = IntArray(n)

        init {
            for (i in 0 until n / 2) {
                val a = -2.0 * PI * i / n
                cosT[i] = cos(a).toFloat()
                sinT[i] = sin(a).toFloat()
            }
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun transform(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + half) {
                        val xr = re[j + half]
                        val xi = im[j + half]
                        val tr = xr * cosT[k] - xi * sinT[k]
                        val ti = xr * sinT[k] + xi * cosT[k]
                        re[j + half] = re[j] - tr
                        im[j + half] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        k += step
                    }
                    i += size
                }
                size = size shl 1
            }
        }
    }
}
