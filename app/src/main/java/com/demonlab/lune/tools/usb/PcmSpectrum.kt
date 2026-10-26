package com.demonlab.lune.tools.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the PCM that ExoPlayer is playing into 48 log-spaced spectrum bars for the app's visualizer.
 * Android's own Visualizer effect needs the microphone permission and does not see ExoPlayer's float output
 * reliably, so the ExoPlayer path computes the spectrum itself from the audio it plays.
 */
class PcmSpectrum(private val onSpectrum: (FloatArray) -> Unit) {
    private var sampleRate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT

    private val n = 1024
    private val bars = 48
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val cosTable = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinTable = FloatArray(n / 2) { -sin(2.0 * PI * it / n).toFloat() }
    private val reversed = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val ring = FloatArray(n)
    private var filled = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private var lastPublishMs = 0L

    /** Called when the sink is (re)configured; [encoding] is the encoding of the buffers that will follow. */
    fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = if (sampleRateHz > 0) sampleRateHz else 44_100
        channels = max(1, channelCount)
        this.encoding = encoding
        filled = 0
    }

    /** [buffer] holds the PCM that was just written to the audio output (position..limit). */
    fun handleBuffer(buffer: ByteBuffer) {
        val buf = buffer.order(ByteOrder.nativeOrder())
        val bytes = when (encoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytes * channels
        while (buf.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) / 128f
                    C.ENCODING_PCM_16BIT -> buf.short / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = buf.get().toInt() and 0xFF
                        val b1 = buf.get().toInt() and 0xFF
                        val b2 = buf.get().toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8_388_608f
                    }
                    C.ENCODING_PCM_32BIT -> buf.int / 2_147_483_648f
                    else -> buf.float
                }
            }
            ring[filled++] = sum / channels
            if (filled == n) {
                filled = 0
                val now = System.currentTimeMillis()
                if (now - lastPublishMs >= 25) {
                    lastPublishMs = now
                    analyze()
                }
            }
        }
    }

    private fun analyze() {
        for (i in 0 until n) {
            re[i] = ring[i] * window[i]
            im[i] = 0f
        }
        fft()
        val binHz = sampleRate / n.toFloat()
        val fMin = 40f
        val fMax = min(12_000f, sampleRate / 2f * 0.95f)
        val ratio = fMax / fMin
        val out = FloatArray(bars)
        for (i in 0 until bars) {
            val f0 = fMin * ratio.pow(i / bars.toFloat())
            val f1 = fMin * ratio.pow((i + 1) / bars.toFloat())
            val lo = max(1, (f0 / binHz).toInt())
            val hi = min(n / 2, max(lo + 1, (f1 / binHz).toInt()))
            var peak = 0f
            for (k in lo until hi) {
                // Hann window halves the amplitude and a real sine splits into two bins: x4/n gives ~full-scale = 1.0
                val amp = sqrt(re[k] * re[k] + im[k] * im[k]) * 4f / n
                if (amp > peak) peak = amp
            }
            val db = 20f * log10(peak + 1e-6f)
            val tilt = 18f * i / (bars - 1)              // lift the quieter high bands
            out[i] = ((db + tilt + 70f) / 60f).coerceIn(0.1f, 1f)
        }
        onSpectrum(out)
    }

    /** In-place radix-2 FFT of [re]/[im]. */
    private fun fft() {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/** Passes everything through to [delegate] and lets [spectrum] look at the PCM that is actually written out. */
@OptIn(UnstableApi::class)
class SpectrumAudioSink(
    delegate: AudioSink,
    private val spectrum: PcmSpectrum,
    private val floatOutputForHighRes: Boolean
) : ForwardingAudioSink(delegate) {

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        val enc = inputFormat.pcmEncoding
        val written = if (enc == C.ENCODING_PCM_FLOAT ||
            (floatOutputForHighRes && (enc == C.ENCODING_PCM_24BIT || enc == C.ENCODING_PCM_32BIT))
        ) C.ENCODING_PCM_FLOAT else enc
        spectrum.flush(inputFormat.sampleRate, inputFormat.channelCount, written)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val start = buffer.position()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val end = buffer.position()
        if (end > start) {
            val seen = buffer.duplicate()
            seen.position(start)
            seen.limit(end)
            spectrum.handleBuffer(seen)
        }
        return handled
    }
}
