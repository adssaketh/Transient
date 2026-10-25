package com.demonlab.lune.tools.usb

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.decent.usbaudio.UsbAudioDevice
import com.decent.usbaudio.UsbAudioPermissionHelper
import com.demonlab.lune.tools.SettingsManager

/**
 * Decides whether bit-perfect USB output can (and should) be used right now, and
 * owns the few places Lune touches the Decent USB driver outside of playback.
 *
 * The Decent libraries need API 29+, while Lune supports API 24. Every call into
 * them is therefore guarded by [isSupported] and wrapped in a `Throwable` catch,
 * so a missing native library can never take the app down.
 */
object UsbDacSupport {

    private const val TAG = "UsbDacSupport"

    /** Why bit-perfect output is (not) available. Order = the order the checks run. */
    enum class Availability {
        /** Android < 10, or the device has no USB host mode. */
        UNSUPPORTED,

        /** The user has not enabled USB bit-perfect output. */
        DISABLED,

        /** Enabled, but no USB Audio device is connected. */
        NO_DAC,

        /** A DAC is connected but the user has not granted Lune access to it. */
        NEEDS_PERMISSION,

        /** Everything in place: the next track can be sent straight to the DAC. */
        READY
    }

    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** First connected device with a USB Audio *streaming* interface — the same test the driver applies. */
    fun findDac(context: Context): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return manager.deviceList.values.firstOrNull { isAudioDevice(it) }
    }

    fun isAudioDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        return (0 until device.interfaceCount).any { i ->
            val iface = device.getInterface(i)
            iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2
        }
    }

    fun displayName(device: UsbDevice): String =
        device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        (context.getSystemService(Context.USB_SERVICE) as? UsbManager)?.hasPermission(device) == true

    fun availability(context: Context, settings: SettingsManager): Availability {
        if (!isSupported(context)) return Availability.UNSUPPORTED
        if (!settings.isUsbBitPerfectEnabled) return Availability.DISABLED
        val dac = findDac(context) ?: return Availability.NO_DAC
        return if (hasPermission(context, dac)) Availability.READY else Availability.NEEDS_PERMISSION
    }

    /**
     * USB_DEVICE_ATTACHED arrived at the main activity. Claims the DAC immediately so the
     * kernel's snd-usb-audio driver cannot bind first (it does so ~3 ms after plug-in).
     * No-op unless the user enabled USB bit-perfect output.
     */
    fun handleAttachIntent(context: Context, intent: Intent?, settings: SettingsManager) {
        if (intent == null || !isSupported(context) || !settings.isUsbBitPerfectEnabled) return
        try {
            UsbAudioPermissionHelper.handleIntent(context.applicationContext, intent)
        } catch (t: Throwable) {
            Log.w(TAG, "USB attach handling failed: ${t.message}")
        }
    }

    /**
     * For a DAC that was already plugged in when the user switched the feature on: ask for
     * permission if needed, then claim it. [onResult] is called on the main thread.
     */
    fun requestAccess(context: Context, onResult: (Boolean) -> Unit = {}) {
        if (!isSupported(context)) { onResult(false); return }
        val dac = findDac(context)
        if (dac == null) { onResult(false); return }
        try {
            val usb = UsbAudioDevice.getInstance(context.applicationContext)
            if (usb.hasPermission(dac)) {
                usb.openDevice(dac)
                onResult(true)
            } else {
                usb.requestPermission(dac) { granted ->
                    if (granted) usb.openDevice(dac)
                    onResult(granted)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "USB access request failed: ${t.message}")
            onResult(false)
        }
    }

    /** DAC unplugged: drop the driver's cached file descriptor so a re-plug starts clean. */
    fun onDeviceDetached(context: Context) {
        if (!isSupported(context)) return
        try {
            UsbAudioDevice.getInstance(context.applicationContext).closeDevice()
        } catch (t: Throwable) {
            Log.w(TAG, "closeDevice failed: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun deviceFromIntent(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
}
