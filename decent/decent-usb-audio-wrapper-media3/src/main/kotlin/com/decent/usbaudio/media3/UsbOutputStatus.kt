package com.decent.usbaudio.media3

/**
 * Read-only snapshot of what [UsbAudioSink] is currently doing with the DAC.
 *
 * Added by the Lune integration so a host app can show a signal-path panel
 * from driver state instead of guessing. It has no effect on audio output.
 */
data class UsbOutputStatus(
    /** Marketing/product name of the DAC when the OS provides one, else the USB device node. */
    val deviceName: String,
    /** Sample rate the DAC clock was set to. */
    val sampleRateHz: Int,
    val channelCount: Int,
    /** Bit depth of the USB alt-setting in use — i.e. what the DAC actually receives. */
    val usbBitDepth: Int,
    /** Source bit depth from the FLAC header; 0 when unknown (non-native route). */
    val sourceBitDepth: Int,
    /** media3 `C.ENCODING_*` value the decoder handed to the sink. */
    val pcmEncoding: Int,
    val route: Route,
    /** UAC2 CLOCK_VALID read-back after the last rate change; null if it was not read. */
    val clockLocked: Boolean?
) {
    enum class Route {
        /** libFLAC decode + USB write inside one native thread. */
        NATIVE_FLAC_ENGINE,

        /** ExoPlayer decoders (libFLAC extractor / FFmpeg) feeding the USB streaming thread. */
        EXOPLAYER_PIPELINE
    }
}
