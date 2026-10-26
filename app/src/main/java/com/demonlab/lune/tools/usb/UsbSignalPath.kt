package com.demonlab.lune.tools.usb

import androidx.media3.common.C
import com.decent.usbaudio.media3.UsbOutputStatus
import com.demonlab.lune.tools.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live state behind the "DAC signal path" screen. Written by [UsbPlaybackEngine] / MusicService,
 * read by Compose. Everything is a [StateFlow], so it is safe to write from the playback thread.
 */
object UsbSignalPathStore {

    /** Track-level format as ExoPlayer sees it, before decoding. */
    data class SourceFormat(
        val sampleMimeType: String?,
        val containerMimeType: String?,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    /** One-off events worth telling the user about on the signal-path screen. */
    enum class Notice { FELL_BACK_TO_STANDARD, DAC_UNPLUGGED }

    enum class Verdict {
        /** Lossless source, rate/depth preserved, DAC clock locked. */
        BIT_PERFECT,

        /** Goes straight to the DAC, but the source was lossy so it is decoded output, not the original bits. */
        DIRECT_DECODED,

        /** The phone volume is below maximum, so the samples are scaled digitally (no longer bit-for-bit). */
        DIGITAL_VOLUME,

        /** Something does not line up (rate mismatch, source deeper than the DAC, clock not locked). */
        CHECK
    }

    private val _active = MutableStateFlow(false)
    /** True while the USB engine — not Android's audio stack — owns playback. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _output = MutableStateFlow<UsbOutputStatus?>(null)
    val output: StateFlow<UsbOutputStatus?> = _output.asStateFlow()

    private val _source = MutableStateFlow<SourceFormat?>(null)
    val source: StateFlow<SourceFormat?> = _source.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _gain = MutableStateFlow(1f)
    /** Digital gain applied to the USB stream (1.0 = untouched). */
    val gain: StateFlow<Float> = _gain.asStateFlow()
    fun setGain(value: Float) { _gain.value = value }

    fun setActive(value: Boolean) {
        _active.value = value
        if (value) _notice.value = null
        if (!value) {
            _output.value = null
            _source.value = null
            _gain.value = 1f
        }
    }

    fun setOutput(status: UsbOutputStatus?) { _output.value = status }
    fun setSource(format: SourceFormat?) { _source.value = format }
    fun postNotice(notice: Notice) { _notice.value = notice }
    // ── Interpretation ────────────────────────────────────────────────────────

    private val LOSSLESS_MIME = setOf(
        "audio/raw", "audio/flac", "audio/alac", "audio/x-ape", "audio/true-hd"
    )
    private val LOSSLESS_EXT = setOf("FLAC", "WAV", "ALAC", "AIFF", "AIF", "APE", "WV", "TTA")

    fun isLossless(source: SourceFormat?, song: Song?): Boolean {
        val mimes = listOfNotNull(source?.sampleMimeType, source?.containerMimeType).map { it.lowercase() }
        if (mimes.any { it in LOSSLESS_MIME }) return true
        // Container metadata can be missing (e.g. before tracks are reported): fall back to the extension.
        return song?.format?.uppercase() in LOSSLESS_EXT
    }

    /**
     * Honest summary of what the driver reports. It reflects configuration (rate, depth, clock lock),
     * not a measurement at the DAC — see the verification guide in decent/docs for hardware-level proof.
     */
    fun verdict(out: UsbOutputStatus?, source: SourceFormat?, song: Song?, gain: Float = 1f): Verdict? {
        if (out == null) return null
        val fileRate = song?.sampleRate?.takeIf { it > 0 } ?: source?.sampleRateHz?.takeIf { it > 0 }
        val rateOk = fileRate == null || fileRate == out.sampleRateHz
        val fileDepth = song?.bitDepth?.takeIf { it > 0 } ?: out.sourceBitDepth.takeIf { it > 0 }
        val depthOk = fileDepth == null || fileDepth <= out.usbBitDepth
        val clockOk = out.clockLocked != false
        if (!rateOk || !depthOk || !clockOk) return Verdict.CHECK
        if (gain < 0.9999f) return Verdict.DIGITAL_VOLUME
        return if (isLossless(source, song)) Verdict.BIT_PERFECT else Verdict.DIRECT_DECODED
    }

    /** Bits per sample of the PCM encoding the decoder handed to the sink (float counts as 32); 0 if unknown. */
    fun encodingBits(pcmEncoding: Int): Int = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }
}
