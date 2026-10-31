package com.demonlab.lune.tools.eq

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.demonlab.lune.tools.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Sits in front of the USB audio sink and runs the headphone EQ ([AutoEqEngine]) on the decoded audio. When the EQ is off,
 * or anything goes wrong, the audio is passed on untouched, so it is bit-perfect exactly as before.
 *
 * It keeps the audio format (same sample type and the same number of bytes), only the sample values change. 16, 24 and 32 bit
 * integer audio and 32 bit float are handled; anything else passes through.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AutoEqAudioSink(delegate: AudioSink) : ForwardingAudioSink(delegate) {

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    private var chain: AutoEqChain? = null
    private var chainVersion = -1L

    /** The EQ has failed once on this stream: it stays off until the next track, and the sound plays untouched. */
    private var failed = false

    /** Processed audio the delegate has not taken completely yet (it is offered again until it is). */
    private var pending: ByteBuffer? = null
    private var scratch: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sampleRate = inputFormat.sampleRate
        channels = inputFormat.channelCount
        encoding = inputFormat.pcmEncoding
        bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }
        failed = false
        chain = null
        chainVersion = -1L
        pending = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        var out = pending
        if (out == null) {
            val chainNow = chainForNow()
            if (chainNow == null) {
                setShaping(false)
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            out = try {
                process(buffer, chainNow)
            } catch (e: Throwable) {
                failed = true
                DiagnosticLogger.error("AUTOEQ", "EQ failed, playing without it until the next track", e)
                null
            }
            if (out == null) {
                setShaping(false)
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            pending = out
            setShaping(true)
        }
        val taken = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (taken) {
            pending = null
            buffer.position(buffer.limit())
        }
        return taken
    }

    override fun flush() {
        pending = null
        chain?.reset()
        super.flush()
    }

    override fun reset() {
        pending = null
        chain = null
        chainVersion = -1L
        setShaping(false)
        super.reset()
    }

    /** The running filters if the EQ is on and this stream can take it, else null. Rebuilt when the settings changed. */
    private fun chainForNow(): AutoEqChain? {
        if (failed || bytesPerSample == 0 || sampleRate <= 0 || channels < 1 || channels > 8) return null
        val active = AutoEqEngine.current() ?: return null
        if (chain == null || chainVersion != active.version) {
            val built = AutoEqChain(active.profile, active.strengthPercent, sampleRate, channels, active.matchLoudness)
            chain = built
            chainVersion = active.version
            DiagnosticLogger.note(
                "AUTOEQ running: '${active.profile.name}' at $sampleRate Hz, $channels ch, " +
                    "preamp ${"%.2f".format(built.preampDb)} dB (${if (active.matchLoudness) "matched" else "safe"}), ${if (built.isEmpty) "no active filters" else "filters on"}"
            )
        }
        val ready = chain ?: return null
        return if (ready.isEmpty) null else ready
    }

    /** Runs [input] through the filters into a new buffer of the same size. [input] itself is left as it was. */
    private fun process(input: ByteBuffer, eq: AutoEqChain): ByteBuffer? {
        val size = input.remaining()
        val frameBytes = channels * bytesPerSample
        if (size < frameBytes) return null
        val frames = size / frameBytes

        var out = scratch
        if (out == null || out.capacity() < size) {
            out = ByteBuffer.allocateDirect(max(size, 65536)).order(ByteOrder.LITTLE_ENDIAN)
            scratch = out
        }
        out!!.clear()
        val src = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                when (encoding) {
                    C.ENCODING_PCM_16BIT -> {
                        val x = src.short.toInt() / 32768.0
                        out.putShort(quantize(eq.process(x, ch), 32768.0, -32768, 32767).toShort())
                    }
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = src.get().toInt() and 0xFF
                        val b1 = src.get().toInt() and 0xFF
                        val b2 = src.get().toInt()
                        val value = (b2 shl 16) or (b1 shl 8) or b0 // b2 keeps its sign
                        val y = quantize(eq.process(value / 8388608.0, ch), 8388608.0, -8388608, 8388607)
                        out.put((y and 0xFF).toByte())
                        out.put(((y shr 8) and 0xFF).toByte())
                        out.put(((y shr 16) and 0xFF).toByte())
                    }
                    C.ENCODING_PCM_32BIT -> {
                        val x = src.int / 2147483648.0
                        val y = (eq.process(x, ch) * 2147483648.0).let {
                            Math.round(it).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        }
                        out.putInt(y.toInt())
                    }
                    else -> { // C.ENCODING_PCM_FLOAT
                        val y = eq.process(src.float.toDouble(), ch).coerceIn(-1.0, 1.0)
                        out.putFloat(y.toFloat())
                    }
                }
            }
        }
        // bytes after the last whole frame (not expected) are passed on as they are
        val rest = size - frames * frameBytes
        if (rest > 0) {
            val tail = ByteArray(rest)
            src.get(tail)
            out.put(tail)
        }
        out.flip()
        return out
    }

    /** A sample as a whole number: scaled, rounded and kept inside the legal range (never wrapping around). */
    private fun quantize(value: Double, scale: Double, low: Int, high: Int): Int {
        val scaled = Math.round(value * scale)
        return when {
            scaled < low -> low
            scaled > high -> high
            else -> scaled.toInt()
        }
    }

    private fun setShaping(on: Boolean) {
        if (AutoEqEngine.isShapingSound != on) AutoEqEngine.isShapingSound = on
    }
}
