package com.demonlab.lune.ui.usb

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.eq.AutoEqEngine
import com.demonlab.lune.ui.components.BouncySwitch

/**
 * The headphone EQ (AutoEq) controls for the Equalizer screen: the on/off switch, one button per saved headphone profile
 * (the one in use is filled in), and buttons to import another profile, change its strength, choose the volume level and
 * remove it. They change the same settings as the Audio settings screen. The headphone EQ works on the USB DAC output,
 * where the normal equalizer below cannot.
 */
@Composable
fun AutoEqCard(
    modifier: Modifier = Modifier,
    textColor: Color,
    textSecondaryColor: Color
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager.getInstance(context) }
    var enabled by remember { mutableStateOf(settings.isAutoEqEnabled) }
    var name by remember { mutableStateOf(settings.autoEqProfileName) }
    var strength by remember { mutableStateOf(settings.autoEqStrength) }
    var matchLoudness by remember { mutableStateOf(settings.isAutoEqMatchLoudness) }
    var profiles by remember { mutableStateOf(AutoEqEngine.savedProfiles(context)) }
    var confirmRemove by remember { mutableStateOf(false) }
    val hasProfile = name.isNotBlank()
    // Load the saved profile state now, so the notice above this card is right even before any song has played.
    LaunchedEffect(Unit) { AutoEqEngine.reload(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val imported = AutoEqEngine.importFromUri(context, uri)
            if (imported != null) {
                name = imported
                enabled = true
                profiles = AutoEqEngine.savedProfiles(context)
                Toast.makeText(context, context.getString(R.string.autoeq_import_ok, imported), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.autoeq_import_bad, Toast.LENGTH_LONG).show()
            }
        }
    }

    if (confirmRemove && hasProfile) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.autoeq_remove_title)) },
            text = { Text(stringResource(R.string.autoeq_remove_message, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    AutoEqEngine.delete(context, name)
                    name = settings.autoEqProfileName
                    enabled = settings.isAutoEqEnabled
                    profiles = AutoEqEngine.savedProfiles(context)
                    Toast.makeText(context, R.string.autoeq_removed, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.autoeq_chip_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = textColor.copy(alpha = 0.08f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.autoeq_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = textColor
                    )
                    Text(
                        text = if (hasProfile) stringResource(R.string.autoeq_card_profile, name) else stringResource(R.string.autoeq_card_no_profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = textSecondaryColor
                    )
                }
                BouncySwitch(
                    checked = enabled && hasProfile,
                    enabled = hasProfile,
                    onCheckedChange = { on ->
                        enabled = on
                        settings.isAutoEqEnabled = on
                        AutoEqEngine.reload(context)
                    },
                    thumbContent = {
                        Icon(
                            imageVector = if (enabled && hasProfile) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize)
                        )
                    }
                )
            }

            val border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
            val colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
            val buttonPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)

            // One button per saved headphone profile; tapping one makes it the profile in use (and switches the EQ on).
            if (profiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (profile in profiles) {
                        if (profile.name == name) {
                            Button(
                                onClick = {},
                                shape = RoundedCornerShape(50),
                                contentPadding = buttonPadding
                            ) {
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    if (AutoEqEngine.select(context, profile.name)) {
                                        name = profile.name
                                        enabled = true
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                border = border,
                                colors = colors,
                                contentPadding = buttonPadding
                            ) {
                                Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(50),
                    border = border,
                    colors = colors,
                    contentPadding = buttonPadding
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.autoeq_chip_import))
                }

                OutlinedButton(
                    onClick = {
                        val next = when {
                            strength > 75 -> 75
                            strength > 50 -> 50
                            strength > 25 -> 25
                            else -> 100
                        }
                        strength = next
                        settings.autoEqStrength = next
                        AutoEqEngine.reload(context)
                    },
                    enabled = hasProfile,
                    shape = RoundedCornerShape(50),
                    border = border,
                    colors = colors,
                    contentPadding = buttonPadding
                ) {
                    Text(stringResource(R.string.autoeq_chip_strength, strength))
                }

                OutlinedButton(
                    onClick = {
                        matchLoudness = !matchLoudness
                        settings.isAutoEqMatchLoudness = matchLoudness
                        AutoEqEngine.reload(context)
                    },
                    enabled = hasProfile,
                    shape = RoundedCornerShape(50),
                    border = border,
                    colors = colors,
                    contentPadding = buttonPadding
                ) {
                    Text(stringResource(if (matchLoudness) R.string.autoeq_chip_level_match else R.string.autoeq_chip_level_safe))
                }

                OutlinedButton(
                    onClick = { confirmRemove = true },
                    enabled = hasProfile,
                    shape = RoundedCornerShape(50),
                    border = border,
                    colors = colors,
                    contentPadding = buttonPadding
                ) {
                    Text(stringResource(R.string.autoeq_chip_remove))
                }
            }
        }
    }
}
