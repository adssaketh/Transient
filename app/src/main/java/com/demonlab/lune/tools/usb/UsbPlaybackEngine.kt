package com.demonlab.lune.tools.usb

import com.demonlab.lune.tools.AiffSupport
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.decent.usbaudio.media3.UsbAudioSink
import com.demonlab.lune.tools.Song

/**
 * Plays one track at a time straight to a USB DAC, bypassing AudioFlinger.
 *
 * MusicService keeps owning the queue, notification, focus and lifecycle; this class only
 * replaces what `MediaPlayer` does in the standard path. Wiring follows the decent-player
 * integration guide: [UsbAudioSink] inside a [DefaultRenderersFactory] that prefers the FFmpeg
 * extension, a LoadControl that stops ExoPlayer re-reading files the native FLAC engine already
 * owns, and `attachToPlayer()` for engine lifecycle.
 *
 * Must be created, used and released on the main thread.
 */
@OptIn(UnstableApi::class)
class UsbPlaybackEngine(
    context: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** First time the current load is ready (duration known). [playing] = it will start immediately. */
        fun onReady(song: Song, playing: Boolean)

        /** The track played to its end. Fired once per [load]. */
        fun onEnded(song: Song)

        /** The DAC path could not be used for [song]; the caller should fall back to standard output. */
        fun onFailure(song: Song, reason: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var usbSink: UsbAudioSink? = null
    @Volatile private var song: Song? = null

    // ExoPlayer may only be touched from the thread it was built on (main). Callers elsewhere in Lune
    // are not guaranteed to be on main, so reads from other threads get a snapshot refreshed on main.
    @Volatile private var snapPositionMs = 0
    @Volatile private var snapDurationMs = 0
    @Volatile private var snapPlaying = false

    // Each load() gets a token so ready/ended/failure fire at most once per track.
    private var loadToken = 0
    private var readyToken = -1
    private var endedToken = -1
    private var pausedByUs = false

    /**
     * If the sink never publishes an output status shortly after the player is ready, the DAC was
     * not opened (unsupported device, lost permission, stream creation failed). The sink would
     * otherwise stay muted and silent, so report it and let the service fall back.
     */
    private val outputWatchdog = Runnable {
        val current = song ?: return@Runnable
        if (usbSink?.outputStatus == null) {
            Log.w(TAG, "USB output never came up for '${current.title}'")
            callbacks.onFailure(current, "USB DAC did not start")
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> announceReady()
                Player.STATE_ENDED -> announceEnded()
            }
        }

        // With a one-item queue the sink ends a finished native-FLAC track by calling player.pause()
        // (it would call seekToNextMediaItem() if there were a next item). Treat an unrequested pause
        // at the very end of the track as "ended".
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady || pausedByUs) return
            val duration = player.duration
            if (duration != C.TIME_UNSET && duration > 0 && player.currentPosition >= duration - END_TOLERANCE_MS) {
                announceEnded()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}", error)
            val current = song ?: return
            callbacks.onFailure(current, error.errorCodeName)
        }

        override fun onTracksChanged(tracks: Tracks) {
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected && group.length > 0) {
                    val format = group.getTrackFormat(0)
                    UsbSignalPathStore.setSource(
                        UsbSignalPathStore.SourceFormat(
                            sampleMimeType = format.sampleMimeType,
                            containerMimeType = format.containerMimeType,
                            sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: 0,
                            channelCount = format.channelCount.takeIf { it > 0 } ?: 0
                        )
                    )
                    return
                }
            }
        }
    }

    private val player: ExoPlayer = buildPlayer()

    private val snapshotTicker = object : Runnable {
        override fun run() {
            refreshSnapshot()
            mainHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    init {
        UsbSignalPathStore.setActive(true)
        mainHandler.post(snapshotTicker)
    }

    private fun buildPlayer(): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(appContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableOffload: Boolean
            ): AudioSink {
                // libFLAC is always bundled, so FLAC reaches the sink as raw integer PCM straight from
                // the extractor (no float math anywhere). Every other format is decoded by FFmpeg,
                // which hands over float32 that the driver converts back exactly (x * 2^N).
                // This matches the decent-player documentation for the "libFLAC present" case.
                val delegate = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .build()
                return UsbAudioSink(delegate, context).also { usbSink = it }
            }
        }
        // The built-in MediaCodec decoders truncate 24-bit sources to 16-bit; FFmpeg does not.
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val loadControl = UsbAudioSink.wrapLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5_000, 15_000, 2_000, 3_000)
                .build()
        ) { usbSink?.isNativeEngineActive == true }

        val exo = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            // Lune manages audio focus and "becoming noisy" itself (see MusicService).
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(false)
            .build()

        usbSink?.attachToPlayer(exo)
        usbSink?.onOutputStatusChanged = { UsbSignalPathStore.setOutput(it) }
        exo.addListener(listener)
        return exo
    }

    // ── State read by MusicService ────────────────────────────────────────────

    val hasMedia: Boolean get() = song != null

    val isPlaying: Boolean
        get() = if (onMain()) readPlaying().also { snapPlaying = it } else snapPlaying

    val positionMs: Int
        get() = if (onMain()) readPosition().also { snapPositionMs = it } else snapPositionMs

    val durationMs: Int
        get() = if (onMain()) readDuration().also { snapDurationMs = it } else snapDurationMs

    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (onMain()) block() else mainHandler.post { block() }
    }

    private fun readPlaying(): Boolean =
        player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun readPosition(): Int =
        player.currentPosition.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun readDuration(): Int {
        val duration = player.duration
        return if (duration != C.TIME_UNSET && duration > 0) {
            duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            (song?.duration ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun refreshSnapshot() {
        snapPlaying = readPlaying()
        snapPositionMs = readPosition()
        snapDurationMs = readDuration()
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    /** Loads [song] and starts (or, with [playWhenReady] = false, just prepares) playback at [startPositionMs]. */
    fun load(song: Song, startPositionMs: Long, playWhenReady: Boolean) = runOnMain {
        mainHandler.removeCallbacks(outputWatchdog)
        this.song = song
        loadToken++
        pausedByUs = !playWhenReady
        UsbSignalPathStore.setSource(null)
        // A unique media id per load makes sure ExoPlayer treats a replay of the same file as a new
        // item, so the sink recreates its native engine (see UsbAudioSink.onMediaItemTransition).
        val item = MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId("${song.id}#$loadToken")
            .build()
        val start = startPositionMs.coerceAtLeast(0L)
        if (AiffSupport.isAiff(song)) {
            // No AIFF extractor exists in Media3: feed the WAV extractor a virtual WAV view of the file.
            val source = ProgressiveMediaSource.Factory(DataSource.Factory { AiffWavDataSource(appContext) })
                .createMediaSource(item)
            player.setMediaSource(source, start)
        } else {
            player.setMediaItem(item, start)
        }
        player.playWhenReady = playWhenReady
        player.prepare()
        refreshSnapshot()
    }

    fun resume() = runOnMain {
        pausedByUs = false
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> {
                // The sink cannot rewind a finished native engine; a fresh load rebuilds it.
                song?.let { load(it, 0L, true) }
                return@runOnMain
            }
        }
        player.play()
        refreshSnapshot()
    }

    fun pause() = runOnMain {
        pausedByUs = true
        player.pause()
        refreshSnapshot()
    }

    fun seekTo(positionMs: Long) = runOnMain {
        player.seekTo(positionMs.coerceAtLeast(0L))
        refreshSnapshot()
    }

    fun release() = runOnMain {
        mainHandler.removeCallbacks(snapshotTicker)
        mainHandler.removeCallbacks(outputWatchdog)
        usbSink?.onOutputStatusChanged = null
        try {
            usbSink?.detachFromPlayer()
            player.removeListener(listener)
            player.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing USB engine: ${t.message}")
        }
        song = null
        UsbSignalPathStore.setActive(false)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun announceReady() {
        if (readyToken == loadToken) return
        readyToken = loadToken
        val current = song ?: return
        mainHandler.removeCallbacks(outputWatchdog)
        mainHandler.postDelayed(outputWatchdog, WATCHDOG_DELAY_MS)
        callbacks.onReady(current, player.playWhenReady)
    }

    private fun announceEnded() {
        if (endedToken == loadToken) return
        endedToken = loadToken
        val current = song ?: return
        callbacks.onEnded(current)
    }

    private companion object {
        const val TAG = "UsbPlaybackEngine"
        const val WATCHDOG_DELAY_MS = 1_500L
        const val END_TOLERANCE_MS = 2_000L
        const val SNAPSHOT_INTERVAL_MS = 500L
    }
}
