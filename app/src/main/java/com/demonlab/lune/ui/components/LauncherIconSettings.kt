package com.demonlab.lune.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.demonlab.lune.R
import com.demonlab.lune.tools.LauncherIcon
import com.demonlab.lune.tools.SettingsManager

/**
 * Customization > Launcher icon: choose a colour for the Transient icon and put it on the home screen.
 * Presets, a custom colour (hue / saturation / brightness) and "From album art" are offered.
 */
@Composable
fun LauncherIconSection() {
    val context = LocalContext.current
    val settings = remember { SettingsManager.getInstance(context) }
    var color by remember { mutableStateOf(settings.launcherIconColor) }
    var showDialog by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(LauncherIcon.isPinned(context)) }

    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlur = settings.isBlurEnabled && ((isDark && settings.isBlurDarkMode) || (!isDark && settings.isBlurLightMode))
    val cardBg = if (hasBlur) {
        if (isDark) Color.White.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val textColor = if (hasBlur) Color.White else MaterialTheme.colorScheme.onSurface
    val subColor = if (hasBlur) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant

    val presets = remember {
        listOf(
            PaletteOption(0, "Pink", Color(0xFFF29B94)),
            PaletteOption(1, "Violet", Color(0xFFAFA9EC)),
            PaletteOption(2, "Mint", Color(0xFF5DCAA5)),
            PaletteOption(3, "Amber", Color(0xFFEF9F27)),
            PaletteOption(4, "Sky", Color(0xFF85B7EB)),
            PaletteOption(5, "Rose", Color(0xFFED93B1)),
            PaletteOption(6, "Lime", Color(0xFF97C459)),
            PaletteOption(7, "Coral", Color(0xFFF0997B))
        )
    }
    val selected = presets.indexOfFirst { it.color.toArgb() == color }

    fun choose(newColor: Int) {
        color = newColor
        settings.launcherIconColor = newColor
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        shape = RoundedCornerShape(28.dp),
        color = cardBg,
        tonalElevation = if (hasBlur) 0.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(ColorUtils.blendARGB(color, 0xFF000000.toInt(), 0.84f))),
                    contentAlignment = Alignment.Center
                ) {
                    BrandLogo(size = 56.dp, coverColor = Color(color))
                }
                Text(
                    text = stringResource(R.string.launcher_icon_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = subColor,
                    modifier = Modifier.weight(1f)
                )
            }

            PaletteSwatchRow(
                options = presets,
                selectedIndex = selected,
                onSelect = { index -> presets.firstOrNull { it.index == index }?.let { choose(it.color.toArgb()) } },
                hasBlurBackground = hasBlur
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDialog = true }) {
                    Text(stringResource(R.string.launcher_icon_custom))
                }
                if (settings.albumArtSeedColor != 0) {
                    OutlinedButton(onClick = { choose(settings.albumArtSeedColor or 0xFF000000.toInt()) }) {
                        Text(stringResource(R.string.launcher_icon_from_album))
                    }
                }
            }

            Button(
                onClick = {
                    val message = when (LauncherIcon.apply(context, color)) {
                        LauncherIcon.Outcome.REQUESTED -> R.string.launcher_icon_toast_requested
                        LauncherIcon.Outcome.UPDATED -> R.string.launcher_icon_toast_updated
                        LauncherIcon.Outcome.UNSUPPORTED -> R.string.launcher_icon_toast_unsupported
                    }
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                    pinned = LauncherIcon.isPinned(context)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(if (pinned) R.string.launcher_icon_update else R.string.launcher_icon_add))
            }
        }
    }

    if (showDialog) {
        val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
        var hue by remember { mutableStateOf(hsv[0]) }
        var sat by remember { mutableStateOf(hsv[1]) }
        var value by remember { mutableStateOf(hsv[2]) }
        val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.launcher_icon_custom)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(current))
                    )
                    Text(stringResource(R.string.launcher_icon_hue), style = MaterialTheme.typography.labelMedium)
                    Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                    Text(stringResource(R.string.launcher_icon_saturation), style = MaterialTheme.typography.labelMedium)
                    Slider(value = sat, onValueChange = { sat = it }, valueRange = 0.1f..1f)
                    Text(stringResource(R.string.launcher_icon_brightness), style = MaterialTheme.typography.labelMedium)
                    Slider(value = value, onValueChange = { value = it }, valueRange = 0.3f..1f)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choose(current)
                    showDialog = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}
