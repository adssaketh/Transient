package com.demonlab.lune.tools.usb

import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.demonlab.lune.tools.SettingsManager
import kotlin.math.pow

/**
 * Bit-perfect USB output bypasses Android's mixer, so the system volume has no effect on it by itself.
 * This watches the phone's *media volume* (hardware buttons, the volume panel, headset keys, even with the
 * screen off) and turns it into a digital gain for the USB stream.
 *
 * The top step is exactly 1.0, so at maximum volume the samples are untouched and output stays bit-perfect.
 */
class UsbVolumeController(
    context: Context,
    private val onGainChanged: (Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val settings = SettingsManager.getInstance(appContext)
    private var started = false
    private var lastLoggedGain = -1f

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    // Fires immediately when a volume key is pressed (the settings observer only follows ~0.5 s later).
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stream = intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) ?: return
            if (stream == AudioManager.STREAM_MUSIC) refresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        // Volume steps are stored as system settings (e.g. volume_music_speaker); any change notifies here.
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            appContext, volumeReceiver, IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        active = this
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        appContext.contentResolver.unregisterContentObserver(observer)
        try { appContext.unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
        if (active === this) active = null
        onGainChanged(1f)
    }

    /** Re-reads the media volume (also call after the user changes the software-volume setting). */
    fun refresh() {
        if (!started) return
        val gain = if (settings.isUsbSoftwareVolumeEnabled) {
            gainFor(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            )
        } else {
            1f
        }
        if (gain != lastLoggedGain) {
            lastLoggedGain = gain
            Log.i("TransientTrace", "media volume refresh: gain=$gain")
            val index = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
            val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString(",") { it.type.toString() } }.getOrNull()
            val routed = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    audioManager.getAudioDevicesForAttributes(
                        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
                    ).joinToString(",") { it.type.toString() }
                } else "n/a"
            }.getOrNull()
            val stored = runCatching {
                listOf("volume_music_speaker", "volume_music_usb_headset", "volume_music_headset", "volume_music").joinToString(" ") {
                    "$it=" + Settings.System.getInt(appContext.contentResolver, it, -1)
                }
            }.getOrNull()
            com.demonlab.lune.tools.DiagnosticLogger.note("VOLUME index=$index gain=$gain outputDeviceTypes=$outputs (2=speaker 22=usb headset 11=usb device) | music routed to types=$routed | stored: $stored")
        }
        onGainChanged(gain)
    }

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        @Volatile
        private var active: UsbVolumeController? = null

        /** Re-reads the volume of the running controller, e.g. after the user flips the setting. */
        fun refreshActive() { active?.refresh() }

        /** Span of the volume scale: the lowest audible step sits about this many dB below maximum. */
        private const val RANGE_DB = 48f

        /** Maps a volume step to a linear gain: max = 1.0 (untouched), then equal dB steps down, 0 = silent. */
        fun gainFor(index: Int, max: Int): Float {
            if (max <= 0 || index >= max) return 1f
            if (index <= 0) return 0f
            val db = -(max - index) * (RANGE_DB / max)
            return 10.0.pow(db / 20.0).toFloat()
        }
    }
}
