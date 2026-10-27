package com.demonlab.lune.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi

/**
 * Read-only check: does this phone offer Android's own bit-perfect USB mode (Android 14+)?
 *
 * It only ASKS the system what it supports and writes the answer to the playback trace (lines starting
 * "BITPERFECT-PROBE"). It never changes any audio setting or routing.
 */
object BitPerfectProbe {
    private val lastRun = HashMap<String, Long>()

    fun run(context: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastRun) {
            val last = lastRun[reason]
            if (last != null && now - last < 20_000L) return
            lastRun[reason] = now
        }
        val appContext = context.applicationContext
        Thread({
            try {
                probe(appContext, reason)
            } catch (t: Throwable) {
                DiagnosticLogger.note("BITPERFECT-PROBE failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, "transient-bp-probe").apply { isDaemon = true; start() }
    }

    private fun probe(context: Context, reason: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        DiagnosticLogger.note(
            "BITPERFECT-PROBE [$reason] android=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL} " +
                "(Android's bit-perfect mode needs Android 14 = 34 or newer)"
        )
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        DiagnosticLogger.note(
            "BITPERFECT-PROBE outputs: " + outputs.joinToString("; ") { "type=${it.type} name=${it.productName} id=${it.id}" } +
                "  (2=speaker 22=usb headset 11=usb device 12=usb accessory)"
        )
        val usb = outputs.filter {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (usb.isEmpty()) {
            DiagnosticLogger.note("BITPERFECT-PROBE no USB output is visible to Android right now (not plugged in, or this app holds it directly)")
            return
        }
        for (device in usb) {
            DiagnosticLogger.note(
                "BITPERFECT-PROBE usb device '${device.productName}': sampleRates=${device.sampleRates.toList()} " +
                    "encodings=${device.encodings.toList()} channelCounts=${device.channelCounts.toList()}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                probeMixer(am, device)
            } else {
                DiagnosticLogger.note("BITPERFECT-PROBE this Android is older than 14: no system bit-perfect mode")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun probeMixer(am: AudioManager, device: AudioDeviceInfo) {
        val supported = am.getSupportedMixerAttributes(device)
        val bitPerfect = supported.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        DiagnosticLogger.note(
            "BITPERFECT-PROBE getSupportedMixerAttributes: total=${supported.size} bitPerfect=${bitPerfect.size} " +
                "(0 bitPerfect = this phone/DAC does not offer the system mode)"
        )
        bitPerfect.take(12).forEach {
            DiagnosticLogger.note(
                "BITPERFECT-PROBE   bit-perfect format: rate=${it.format.sampleRate} encoding=${it.format.encoding} channelMask=${it.format.channelMask}"
            )
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val current = am.getPreferredMixerAttributes(attrs, device)
        DiagnosticLogger.note(
            "BITPERFECT-PROBE mixer attributes currently preferred for media: " +
                (current?.let { "behavior=${it.mixerBehavior} rate=${it.format.sampleRate}" } ?: "none")
        )
    }
}
