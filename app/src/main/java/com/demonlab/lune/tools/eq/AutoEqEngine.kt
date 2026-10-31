package com.demonlab.lune.tools.eq

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demonlab.lune.tools.DiagnosticLogger
import com.demonlab.lune.tools.SettingsManager
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The headphone EQ the USB DAC output uses: which AutoEq profile is on and how strong it is. The settings screen changes it
 * ([reload] after any change); the audio thread only reads [current].
 */
object AutoEqEngine {
    /** A profile that is switched on, and the strength it runs at. [version] changes whenever either changes. */
    class Active(val profile: AutoEqProfile, val strengthPercent: Int, val version: Long)

    @Volatile
    private var active: Active? = null
    private var versionCounter = 0L

    /** True while the DAC output is really being changed by the EQ. The signal-path screen shows "not bit-perfect" then. */
    var isShapingSound by mutableStateOf(false)

    private const val MAX_FILE_BYTES = 5_000_000
    private const val MAX_TEXT_CHARS = 100_000

    fun current(): Active? = active

    /** Reads the saved settings again. Call after the profile, the switch or the strength has changed. */
    fun reload(context: Context) {
        val settings = SettingsManager.getInstance(context)
        val profile = if (settings.isAutoEqEnabled) {
            AutoEqProfile.parse(settings.autoEqProfileText, settings.autoEqProfileName)
        } else null
        versionCounter += 1
        active = profile?.let { Active(it, settings.autoEqStrength, versionCounter) }
        DiagnosticLogger.note(
            "AUTOEQ settings: " + if (profile == null) "off" else "profile '${profile.name}' with ${profile.filters.size} filters at ${settings.autoEqStrength}%"
        )
    }

    /**
     * Imports a profile the user picked: a ParametricEQ.txt, or a zip that holds one (as downloaded from AutoEq's page).
     * The profile is saved and switched on. Returns its name, or null when the file holds no usable profile.
     */
    fun importFromUri(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: ""
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (bytes.size > MAX_FILE_BYTES) return null
            val (text, nameHint) = readText(bytes, displayName) ?: return null
            val profile = AutoEqProfile.parse(text, cleanName(nameHint)) ?: return null
            val settings = SettingsManager.getInstance(context)
            settings.autoEqProfileText = text
            settings.autoEqProfileName = profile.name
            settings.isAutoEqEnabled = true
            reload(context)
            profile.name
        } catch (e: Exception) {
            DiagnosticLogger.error("AUTOEQ", "Import failed", e)
            null
        }
    }

    /** The profile text of a plain file, or of the ParametricEQ.txt inside a zip, with the name to call it. */
    private fun readText(bytes: ByteArray, displayName: String): Pair<String, String>? {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (!isZip) return bytes.decodeToString().take(MAX_TEXT_CHARS) to displayName
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.substringAfterLast('/')
                if (entryName.endsWith("ParametricEQ.txt", ignoreCase = true)) {
                    return zip.readBytes().decodeToString().take(MAX_TEXT_CHARS) to entryName
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** "Tin HiFi T3 Plus ParametricEQ.txt" -> "Tin HiFi T3 Plus". */
    private fun cleanName(raw: String): String {
        val name = raw.substringAfterLast('/')
            .removeSuffix(".txt").removeSuffix(".TXT")
            .replace(" ParametricEQ", "", ignoreCase = true)
            .trim()
        return name.ifBlank { "AutoEq profile" }
    }
}
