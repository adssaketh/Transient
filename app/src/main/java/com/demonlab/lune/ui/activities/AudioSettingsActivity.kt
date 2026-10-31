package com.demonlab.lune.ui.activities

import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.demonlab.lune.tools.usb.UsbVolumeController
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.demonlab.lune.tools.eq.AutoEqEngine
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FileDownload
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.BouncySwitch
import androidx.compose.ui.graphics.Color
import com.demonlab.lune.tools.PlaybackManager
import com.demonlab.lune.tools.usb.UsbDacSupport
import com.demonlab.lune.tools.usb.UsbSignalPathStore
import com.demonlab.lune.ui.usb.formatBitsAndRate
import kotlinx.coroutines.delay
import com.demonlab.lune.ui.components.AppBlurBackdrop
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.utils.bounceClick

class AudioSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val themeMode = settingsManager.themeMode
            val systemInDarkTheme = isSystemInDarkTheme()
            val targetDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme
            }
            var useCustomColors by remember { mutableStateOf(settingsManager.useCustomColors) }
            var customColorPalette by remember { mutableIntStateOf(settingsManager.customColorPalette) }
            var useAmoledPitchBlack by remember { mutableStateOf(settingsManager.useAmoledPitchBlack) }

            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = useCustomColors,
                customColorPalette = customColorPalette,
                useAmoledPitchBlack = useAmoledPitchBlack
            ) {
                AudioSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val currentSong = playbackManager.currentSong
    val themeMode = settingsManager.themeMode
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = settingsManager.isBlurEnabled && ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))

    var enableHiFi by remember { mutableStateOf(settingsManager.enableHiFi) }
    var is32BitFloatEnabled by remember { mutableStateOf(settingsManager.is32BitFloatEnabled) }
    var isExclusiveModeEnabled by remember { mutableStateOf(settingsManager.isExclusiveModeEnabled) }
    var stopOnTaskRemoved by remember { mutableStateOf(settingsManager.stopOnTaskRemoved) }

    // ── USB bit-perfect output ──
    var usbEnabled by remember { mutableStateOf(settingsManager.isUsbBitPerfectEnabled) }
    var usbSoftwareVolume by remember { mutableStateOf(settingsManager.isUsbSoftwareVolumeEnabled) }
    val usbSupported = remember { UsbDacSupport.isSupported(context) }
    val usbActive by UsbSignalPathStore.active.collectAsState()
    val usbOutput by UsbSignalPathStore.output.collectAsState()
    var usbAvailability by remember { mutableStateOf(UsbDacSupport.availability(context, settingsManager)) }
    var usbDacName by remember { mutableStateOf<String?>(null) }
    // Plugging a DAC in or granting permission doesn't touch any flow we observe, so re-check every second.
    LaunchedEffect(usbEnabled) {
        while (true) {
            usbAvailability = UsbDacSupport.availability(context, settingsManager)
            usbDacName = if (usbSupported) UsbDacSupport.findDac(context)?.let { UsbDacSupport.displayName(it) } else null
            delay(1_000)
        }
    }
    val usbOut = usbOutput
    val usbSummary = when {
        !usbSupported -> stringResource(R.string.usb_state_unsupported)
        usbActive && usbOut != null ->
            stringResource(R.string.usb_status_active, formatBitsAndRate(usbOut.usbBitDepth, usbOut.sampleRateHz))
        usbAvailability == UsbDacSupport.Availability.READY ->
            stringResource(R.string.usb_status_ready, usbDacName ?: stringResource(R.string.output_usb_dac))
        usbAvailability == UsbDacSupport.Availability.NO_DAC -> stringResource(R.string.usb_status_no_dac)
        usbAvailability == UsbDacSupport.Availability.NEEDS_PERMISSION -> stringResource(R.string.usb_status_permission)
        else -> stringResource(R.string.usb_status_off)
    }

    // ── Headphone EQ (AutoEq) ──
    var autoEqEnabled by remember { mutableStateOf(settingsManager.isAutoEqEnabled) }
    var autoEqName by remember { mutableStateOf(settingsManager.autoEqProfileName) }
    var autoEqStrength by remember { mutableStateOf(settingsManager.autoEqStrength) }
    val autoEqPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val imported = AutoEqEngine.importFromUri(context, uri)
            if (imported != null) {
                autoEqName = imported
                autoEqEnabled = true
                Toast.makeText(context, context.getString(R.string.autoeq_import_ok, imported), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.autoeq_import_bad, Toast.LENGTH_LONG).show()
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AppBlurBackdrop(
        hasBlurBackground = hasBlurBackground,
        isDarkTheme = isDarkTheme,
        currentSong = currentSong
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (hasBlurBackground && currentSong != null) Color.Transparent else MaterialTheme.colorScheme.surface,
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.audio_settings),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.primary
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.bounceClick()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (hasBlurBackground && currentSong != null) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back),
                                        tint = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (hasBlurBackground && currentSong != null) Color.Transparent else MaterialTheme.colorScheme.surface,
                        titleContentColor = if (hasBlurBackground && currentSong != null) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsSection(title = stringResource(R.string.audio_settings)) {
                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.hifi_badge),
                    supportingText = stringResource(R.string.hifi_badge_desc),
                    icon = Icons.Default.MusicNote,
                    position = SectionPosition.FIRST,
                    trailingContent = {
                        BouncySwitch(
                            checked = enableHiFi,
                            onCheckedChange = {
                                enableHiFi = it
                                settingsManager.enableHiFi = it
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (enableHiFi) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.audio_32bit_float),
                    supportingText = stringResource(R.string.audio_32bit_float_desc),
                    icon = Icons.Default.Equalizer,
                    position = SectionPosition.MIDDLE,
                    trailingContent = {
                        BouncySwitch(
                            checked = is32BitFloatEnabled,
                            onCheckedChange = {
                                is32BitFloatEnabled = it
                                settingsManager.is32BitFloatEnabled = it
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (is32BitFloatEnabled) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.audio_exclusive_mode),
                    supportingText = stringResource(R.string.audio_exclusive_mode_desc),
                    icon = Icons.Default.HighQuality,
                    position = SectionPosition.MIDDLE,
                    trailingContent = {
                        BouncySwitch(
                            checked = isExclusiveModeEnabled,
                            onCheckedChange = {
                                isExclusiveModeEnabled = it
                                settingsManager.isExclusiveModeEnabled = it
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (isExclusiveModeEnabled) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.stop_on_task_removed),
                    supportingText = stringResource(R.string.stop_on_task_removed_desc),
                    icon = Icons.AutoMirrored.Filled.ExitToApp,
                    position = SectionPosition.LAST,
                    trailingContent = {
                        BouncySwitch(
                            checked = stopOnTaskRemoved,
                            onCheckedChange = {
                                stopOnTaskRemoved = it
                                settingsManager.stopOnTaskRemoved = it
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (stopOnTaskRemoved) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSection(title = stringResource(R.string.usb_section_title)) {
                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.usb_bit_perfect),
                    supportingText = stringResource(
                        if (usbSupported) R.string.usb_bit_perfect_desc else R.string.usb_bit_perfect_unsupported
                    ),
                    icon = Icons.Default.Usb,
                    position = SectionPosition.FIRST,
                    trailingContent = {
                        BouncySwitch(
                            checked = usbEnabled && usbSupported,
                            enabled = usbSupported,
                            onCheckedChange = { enabled ->
                                usbEnabled = enabled
                                settingsManager.isUsbBitPerfectEnabled = enabled
                                if (enabled) {
                                    // A DAC that was already plugged in needs permission + claiming now.
                                    if (UsbDacSupport.findDac(context) != null) {
                                        UsbDacSupport.requestAccess(context) { granted ->
                                            if (!granted) {
                                                Toast.makeText(
                                                    context,
                                                    R.string.usb_permission_denied_toast,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                } else if (!usbActive) {
                                    // Hand the DAC back to Android (a re-plug may be needed on some devices).
                                    UsbDacSupport.onDeviceDetached(context)
                                }
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (usbEnabled && usbSupported) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.usb_software_volume),
                    supportingText = stringResource(R.string.usb_software_volume_desc),
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    position = SectionPosition.MIDDLE,
                    trailingContent = {
                        BouncySwitch(
                            checked = usbSoftwareVolume,
                            enabled = usbSupported,
                            onCheckedChange = { enabled ->
                                usbSoftwareVolume = enabled
                                settingsManager.isUsbSoftwareVolumeEnabled = enabled
                                UsbVolumeController.refreshActive()
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (usbSoftwareVolume) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.usb_signal_path),
                    supportingText = usbSummary,
                    icon = Icons.Default.GraphicEq,
                    position = SectionPosition.LAST,
                    onClick = { context.startActivity(Intent(context, SignalPathActivity::class.java)) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsSection(title = stringResource(R.string.autoeq_section_title)) {
                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.autoeq_switch),
                    supportingText = if (autoEqName.isBlank()) {
                        stringResource(R.string.autoeq_switch_desc_none)
                    } else {
                        stringResource(R.string.autoeq_switch_desc, autoEqName)
                    },
                    icon = Icons.Default.Equalizer,
                    position = SectionPosition.FIRST,
                    trailingContent = {
                        BouncySwitch(
                            checked = autoEqEnabled && autoEqName.isNotBlank(),
                            enabled = autoEqName.isNotBlank(),
                            onCheckedChange = { enabled ->
                                autoEqEnabled = enabled
                                settingsManager.isAutoEqEnabled = enabled
                                AutoEqEngine.reload(context)
                            },
                            thumbContent = {
                                Icon(
                                    imageVector = if (autoEqEnabled && autoEqName.isNotBlank()) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.autoeq_import),
                    supportingText = stringResource(R.string.autoeq_import_desc),
                    icon = Icons.Default.FileDownload,
                    position = SectionPosition.MIDDLE,
                    onClick = { autoEqPicker.launch(arrayOf("*/*")) }
                )

                SettingsPreferenceItem(
                    headlineText = stringResource(R.string.autoeq_strength),
                    supportingText = stringResource(R.string.autoeq_strength_desc, autoEqStrength),
                    icon = Icons.Default.Tune,
                    position = SectionPosition.LAST,
                    onClick = {
                        val next = when {
                            autoEqStrength > 75 -> 75
                            autoEqStrength > 50 -> 50
                            autoEqStrength > 25 -> 25
                            else -> 100
                        }
                        autoEqStrength = next
                        settingsManager.autoEqStrength = next
                        AutoEqEngine.reload(context)
                    }
                )
            }
        }
    }
}
}
