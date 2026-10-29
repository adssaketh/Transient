package com.demonlab.lune.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * Plays inaudible silence on the phone's own speaker output while a USB DAC is being driven directly.
 *
 * Direct USB output bypasses Android's audio system, so as far as Android can tell the app is not playing anything.
 * Some phones then freeze the whole app for about two seconds whenever another app is starting (seen in the playback
 * trace as "HEARTBEAT late" / "GAP"). An active media stream, even a silent one, lets Android see that audio is playing.
 *
 * It is routed to the built-in speaker only, never to the DAC, and only runs while a song is playing.
 */
object SilentAudioKeepAlive {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start(context: Context) {
        synchronized(lock) {
            if (running) return
            if (!SettingsManager.getInstance(context.applicationContext).usbKeepAliveEnabled) {
                DiagnosticLogger.note("KEEPALIVE off (switched off in Settings > Diagnostics)")
                return
            }
            try {
                val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker == null) {
                    DiagnosticLogger.note("KEEPALIVE not started: no built-in speaker output found")
                    return
                }
                // A normal music stream, like USB Audio Player PRO's "Play silence on Android audio": 48 kHz stereo through the
                // regular mixer. The earlier 8 kHz mono low-power stream went to the phone's power-saving output path, which
                // (like UAPP's "bypass system EQ" mode, which it warns "will cause pauses when switching apps") did not stop the
                // pauses.
                val rate = 48000
                val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    DiagnosticLogger.note("KEEPALIVE not started: bad buffer size $minBuf")
                    return
                }
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, rate / 5 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                val t = builder.build()
                if (!t.setPreferredDevice(speaker)) {
                    t.release()
                    DiagnosticLogger.note("KEEPALIVE not started: could not route to the speaker")
                    return
                }
                t.play()
                track = t
                running = true
                val silence = ByteArray(rate / 5 * 4) // 200 ms of zeros, 16-bit stereo
                thread = Thread({
                    try {
                        while (running) {
                            if (t.write(silence, 0, silence.size) < 0) break
                        }
                    } catch (_: Throwable) {
                    }
                }, "transient-keepalive").apply { isDaemon = true; start() }
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val exempt = runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
                val saver = runCatching { pm.isPowerSaveMode }.getOrNull()
                DiagnosticLogger.note("KEEPALIVE started (silent 48 kHz stereo music stream, normal output path, on built-in speaker) | battery optimisation ignored=$exempt power saver=$saver")
            } catch (e: Throwable) {
                running = false
                DiagnosticLogger.note("KEEPALIVE start failed: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running && track == null) return
            running = false
            runCatching { track?.pause() }
            runCatching { track?.release() }
            track = null
            thread = null
            DiagnosticLogger.note("KEEPALIVE stopped")
        }
    }
}
