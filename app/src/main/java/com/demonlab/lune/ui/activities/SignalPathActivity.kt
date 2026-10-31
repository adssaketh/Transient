package com.demonlab.lune.ui.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.decent.usbaudio.media3.UsbOutputStatus
import com.demonlab.lune.R
import com.demonlab.lune.tools.PlaybackManager
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.Song
import com.demonlab.lune.tools.usb.UsbDacSupport
import com.demonlab.lune.tools.usb.UsbSignalPathStore
import com.demonlab.lune.tools.usb.UsbSignalPathStore.Verdict
import com.demonlab.lune.ui.components.AppBlurBackdrop
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.usb.formatBitsAndRate
import com.demonlab.lune.ui.usb.formatSampleRate
import com.demonlab.lune.ui.utils.bounceClick
import kotlinx.coroutines.delay

/**
 * "DAC signal path": a live, honest picture of what happens between the file and the DAC —
 * source, decoder, processing, USB driver, DAC — plus a one-line verdict.
 */
class SignalPathActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()
            val targetDarkTheme = when (settingsManager.themeMode) {
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
                SignalPathScreen(onBack = { finish() })
            }
        }
    }
}

/** What the hero card should say. */
private enum class PathState { ACTIVE, UNSUPPORTED, OFF, NO_DAC, NEEDS_PERMISSION, IDLE, STANDARD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalPathScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val playbackManager = remember { PlaybackManager.getInstance(context) }
    val currentSong = playbackManager.currentSong

    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = settingsManager.isBlurEnabled &&
        ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))
    val onBlur = hasBlurBackground && currentSong != null

    val active by UsbSignalPathStore.active.collectAsState()
    val output by UsbSignalPathStore.output.collectAsState()
    val source by UsbSignalPathStore.source.collectAsState()
    val notice by UsbSignalPathStore.notice.collectAsState()
    val gain by UsbSignalPathStore.gain.collectAsState()

    // Plugging a DAC in or granting permission doesn't touch any of our flows, so re-check once a second.
    var availability by remember { mutableStateOf(UsbDacSupport.availability(context, settingsManager)) }
    var dacName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            availability = UsbDacSupport.availability(context, settingsManager)
            dacName = if (UsbDacSupport.isSupported(context)) {
                UsbDacSupport.findDac(context)?.let { UsbDacSupport.displayName(it) }
            } else null
            delay(1_000)
        }
    }

    val state = when {
        active && output != null -> PathState.ACTIVE
        availability == UsbDacSupport.Availability.UNSUPPORTED -> PathState.UNSUPPORTED
        availability == UsbDacSupport.Availability.DISABLED -> PathState.OFF
        availability == UsbDacSupport.Availability.NO_DAC -> PathState.NO_DAC
        availability == UsbDacSupport.Availability.NEEDS_PERMISSION -> PathState.NEEDS_PERMISSION
        playbackManager.isPlaying && !active -> PathState.STANDARD
        else -> PathState.IDLE
    }
    val verdict = if (state == PathState.ACTIVE) UsbSignalPathStore.verdict(output, source, currentSong, gain) else null

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AppBlurBackdrop(
        hasBlurBackground = hasBlurBackground,
        isDarkTheme = isDarkTheme,
        currentSong = currentSong
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (onBlur) Color.Transparent else MaterialTheme.colorScheme.surface,
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.usb_signal_path),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (onBlur) Color.White else MaterialTheme.colorScheme.primary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.bounceClick()) {
                            Surface(
                                shape = CircleShape,
                                color = if (onBlur) Color.White.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back),
                                        tint = if (onBlur) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (onBlur) Color.Transparent else MaterialTheme.colorScheme.surface,
                        titleContentColor = if (onBlur) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                HeroCard(state = state, verdict = verdict, output = output)

                notice?.let { NoticeCard(it) }

                if (state == PathState.ACTIVE) {
                    output?.let { out ->
                        PathCard(
                            song = currentSong,
                            source = source,
                            out = out,
                            verdict = verdict,
                            gain = gain
                        )
                    }
                } else if (state == PathState.OFF || state == PathState.NO_DAC || state == PathState.NEEDS_PERMISSION) {
                    dacName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (onBlur) Color.White else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                if (state != PathState.ACTIVE && state != PathState.UNSUPPORTED) {
                    FilledTonalButton(
                        onClick = { context.startActivity(Intent(context, AudioSettingsActivity::class.java)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .bounceClick()
                    ) {
                        Text(stringResource(R.string.usb_open_audio_settings))
                    }
                }

                if (state == PathState.ACTIVE) {
                    Text(
                        text = stringResource(R.string.usb_footnote),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (onBlur) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroCard(state: PathState, verdict: Verdict?, output: UsbOutputStatus?) {
    val scheme = MaterialTheme.colorScheme

    val container: Color
    val content: Color
    val icon: ImageVector
    val title: String
    val description: String

    when {
        state == PathState.ACTIVE && verdict == Verdict.BIT_PERFECT -> {
            container = scheme.primaryContainer; content = scheme.onPrimaryContainer
            icon = Icons.Filled.Verified
            title = stringResource(R.string.usb_verdict_bit_perfect)
            description = stringResource(R.string.usb_verdict_bit_perfect_desc)
        }
        state == PathState.ACTIVE && verdict == Verdict.DIGITAL_VOLUME -> {
            container = scheme.tertiaryContainer; content = scheme.onTertiaryContainer
            icon = Icons.Filled.Usb
            title = stringResource(R.string.usb_verdict_volume)
            description = stringResource(R.string.usb_verdict_volume_desc)
        }
        state == PathState.ACTIVE && verdict == Verdict.EQUALIZED -> {
            container = scheme.tertiaryContainer; content = scheme.onTertiaryContainer
            icon = Icons.Filled.Usb
            title = stringResource(R.string.usb_verdict_eq)
            description = stringResource(R.string.usb_verdict_eq_desc)
        }
        state == PathState.ACTIVE && verdict == Verdict.DIRECT_DECODED -> {
            container = scheme.secondaryContainer; content = scheme.onSecondaryContainer
            icon = Icons.Filled.Usb
            title = stringResource(R.string.usb_verdict_direct)
            description = stringResource(R.string.usb_verdict_direct_desc)
        }
        state == PathState.ACTIVE -> {
            container = scheme.errorContainer; content = scheme.onErrorContainer
            icon = Icons.Filled.Warning
            title = stringResource(R.string.usb_verdict_check)
            description = stringResource(R.string.usb_verdict_check_desc)
        }
        else -> {
            container = scheme.surfaceContainerHigh; content = scheme.onSurface
            icon = Icons.Filled.Usb
            val (t, d) = when (state) {
                PathState.UNSUPPORTED -> R.string.usb_state_unsupported to R.string.usb_state_unsupported_desc
                PathState.OFF -> R.string.usb_state_off to R.string.usb_state_off_desc
                PathState.NO_DAC -> R.string.usb_state_no_dac to R.string.usb_state_no_dac_desc
                PathState.NEEDS_PERMISSION -> R.string.usb_state_permission to R.string.usb_state_permission_desc
                PathState.STANDARD -> R.string.usb_state_standard to R.string.usb_state_standard_desc
                else -> R.string.usb_state_idle to R.string.usb_state_idle_desc
            }
            title = stringResource(t)
            description = stringResource(d)
        }
    }

    Surface(shape = RoundedCornerShape(28.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(40.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = content
            )
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = content)

            if (state == PathState.ACTIVE && output != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(formatSampleRate(output.sampleRateHz), content)
                    Chip("${output.usbBitDepth}-bit", content)
                    Chip("${output.channelCount} ch", content)
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, contentColor: Color) {
    Surface(shape = RoundedCornerShape(50), color = contentColor.copy(alpha = 0.12f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun NoticeCard(notice: UsbSignalPathStore.Notice) {
    val text = when (notice) {
        UsbSignalPathStore.Notice.FELL_BACK_TO_STANDARD -> R.string.usb_notice_fallback
        UsbSignalPathStore.Notice.DAC_UNPLUGGED -> R.string.usb_notice_unplugged
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private data class Stage(
    val icon: ImageVector,
    val title: String,
    val main: String,
    val detail: String? = null,
    val warn: Boolean = false
)

@Composable
private fun PathCard(
    song: Song?,
    source: UsbSignalPathStore.SourceFormat?,
    out: UsbOutputStatus,
    verdict: Verdict?,
    gain: Float
) {
    val fileRate = song?.sampleRate?.takeIf { it > 0 } ?: source?.sampleRateHz?.takeIf { it > 0 }
    val fileBits = song?.bitDepth?.takeIf { it > 0 } ?: out.sourceBitDepth.takeIf { it > 0 }
    val sourceMain = listOfNotNull(
        song?.format?.takeIf { it.isNotBlank() },
        formatBitsAndRate(fileBits, fileRate).ifBlank { null }
    ).joinToString(" · ").ifBlank { "—" }

    val bits = UsbSignalPathStore.encodingBits(out.pcmEncoding)
    val isFloat = out.pcmEncoding == androidx.media3.common.C.ENCODING_PCM_FLOAT
    val decoderMain: String
    val decoderDetail: String
    when {
        out.route == UsbOutputStatus.Route.NATIVE_FLAC_ENGINE -> {
            decoderMain = stringResource(R.string.usb_decoder_native)
            decoderDetail = stringResource(R.string.usb_decoder_native_detail)
        }
        isFloat -> {
            decoderMain = stringResource(R.string.usb_decoder_float)
            decoderDetail = stringResource(R.string.usb_decoder_float_detail)
        }
        else -> {
            decoderMain = stringResource(R.string.usb_decoder_int) + if (bits > 0) " · $bits-bit" else ""
            decoderDetail = stringResource(R.string.usb_decoder_int_detail)
        }
    }

    val clockText = stringResource(
        when (out.clockLocked) {
            true -> R.string.usb_clock_locked
            false -> R.string.usb_clock_unconfirmed
            null -> R.string.usb_clock_unknown
        }
    )

    val checkRate = verdict == Verdict.CHECK
    val stages = listOf(
        Stage(Icons.Filled.MusicNote, stringResource(R.string.usb_stage_source), sourceMain, song?.title),
        Stage(Icons.Filled.Memory, stringResource(R.string.usb_stage_decoder), decoderMain, decoderDetail),
        if (gain < 0.9999f) {
            val db = 20.0 * kotlin.math.log10(gain.toDouble().coerceAtLeast(1e-6))
            Stage(
                Icons.Filled.Tune,
                stringResource(R.string.usb_stage_processing),
                stringResource(R.string.usb_processing_volume, String.format(java.util.Locale.getDefault(), "%.0f dB", db)),
                stringResource(R.string.usb_processing_volume_detail)
            )
        } else {
            Stage(
                Icons.Filled.Tune,
                stringResource(R.string.usb_stage_processing),
                stringResource(R.string.usb_processing_none),
                stringResource(R.string.usb_processing_none_detail)
            )
        },
        Stage(
            Icons.Filled.Usb,
            stringResource(R.string.usb_stage_driver),
            stringResource(R.string.usb_driver_detail, clockText),
            null,
            warn = out.clockLocked == false
        ),
        Stage(
            Icons.Filled.Headphones,
            stringResource(R.string.usb_stage_dac),
            out.deviceName.ifBlank { stringResource(R.string.output_usb_dac) },
            stringResource(
                R.string.usb_dac_detail,
                formatBitsAndRate(out.usbBitDepth, out.sampleRateHz),
                out.channelCount
            ),
            warn = checkRate
        )
    )

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            stages.forEachIndexed { index, stage ->
                StageRow(stage = stage, isLast = index == stages.lastIndex)
            }
        }
    }
}

@Composable
private fun StageRow(stage: Stage, isLast: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dot = if (stage.warn) scheme.errorContainer else scheme.primaryContainer
    val dotTint = if (stage.warn) scheme.onErrorContainer else scheme.onPrimaryContainer

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(dot, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(stage.icon, contentDescription = null, tint = dotTint, modifier = Modifier.size(20.dp))
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(scheme.outlineVariant)
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(start = 16.dp, bottom = if (isLast) 0.dp else 20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stage.title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.primary
            )
            Text(
                text = stage.main,
                style = MaterialTheme.typography.titleMedium,
                color = if (stage.warn) scheme.error else scheme.onSurface
            )
            stage.detail?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
