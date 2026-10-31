package com.demonlab.lune.ui.usb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.usb.UsbSignalPathStore
import java.util.Locale

/** 44100 -> "44.1 kHz", 96000 -> "96 kHz". */
fun formatSampleRate(hz: Int): String {
    if (hz <= 0) return "—"
    return if (hz % 1000 == 0) "${hz / 1000} kHz"
    else String.format(Locale.getDefault(), "%.1f kHz", hz / 1000.0)
}

/** "24-bit / 96 kHz"; omits whichever half is unknown. */
fun formatBitsAndRate(bits: Int?, hz: Int?): String =
    listOfNotNull(
        bits?.takeIf { it > 0 }?.let { "$it-bit" },
        hz?.takeIf { it > 0 }?.let { formatSampleRate(it) }
    ).joinToString(" / ")

/**
 * Shown on the equalizer/effects screens while the USB engine owns playback, because those controls
 * act on Android's audio session and have no effect on bit-perfect output.
 */
@Composable
fun UsbBypassBanner(modifier: Modifier = Modifier) {
    val active by UsbSignalPathStore.active.collectAsState()
    if (!active) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Usb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = stringResource(
                    if (com.demonlab.lune.tools.eq.AutoEqEngine.isOn) R.string.usb_eq_banner_autoeq else R.string.usb_eq_banner
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
