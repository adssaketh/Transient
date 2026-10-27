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
            try {
                val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker == null) {
                    DiagnosticLogger.note("KEEPALIVE not started: no built-in speaker output found")
                    return
                }
                val rate = 8000
                val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                }
                val t = builder.build()
                if (!t.setPreferredDevice(speaker)) {
                    t.release()
                    DiagnosticLogger.note("KEEPALIVE not started: could not route to the speaker")
                    return
                }
                t.play()
                track = t
                running = true
                val silence = ByteArray(rate / 5 * 2) // 200 ms of zeros (16-bit mono)
                thread = Thread({
                    try {
                        while (running) {
                            if (t.write(silence, 0, silence.size) < 0) break
                        }
                    } catch (_: Throwable) {
                    }
                }, "transient-keepalive").apply { isDaemon = true; start() }
                DiagnosticLogger.note("KEEPALIVE started (silent stream on built-in speaker)")
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
