package com.demonlab.lune.ui.activities

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.Song
import com.demonlab.lune.tools.SpectrogramAnalyzer
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.utils.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Spectrogram of a single song: frequency (up) against time (across), colour = level.
 * Shows what is really inside the file — the upper cut-off of a lossy encode, upsampled "hi-res" files, clipping, etc.
 *
 * Gestures: pinch to zoom (horizontal spread zooms time, vertical spread zooms frequency), drag to pan,
 * tap to read time / frequency / level at a point, double-tap to switch colours.
 */
class SpectrogramActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }
        val request = SpectrogramRequest(
            uri = Uri.parse(uriString),
            title = intent.getStringExtra(EXTRA_TITLE) ?: "",
            artist = intent.getStringExtra(EXTRA_ARTIST) ?: "",
            path = intent.getStringExtra(EXTRA_PATH) ?: "",
            format = intent.getStringExtra(EXTRA_FORMAT) ?: "",
            durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        )
        val settingsManager = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()
            val targetDarkTheme = when (settingsManager.themeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme
            }
            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = settingsManager.useCustomColors,
                customColorPalette = settingsManager.customColorPalette,
                useAmoledPitchBlack = settingsManager.useAmoledPitchBlack
            ) {
                SpectrogramScreen(request = request, onBack = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "spectrogram_uri"
        private const val EXTRA_TITLE = "spectrogram_title"
        private const val EXTRA_ARTIST = "spectrogram_artist"
        private const val EXTRA_PATH = "spectrogram_path"
        private const val EXTRA_FORMAT = "spectrogram_format"
        private const val EXTRA_DURATION_MS = "spectrogram_duration_ms"

        fun start(context: Context, song: Song) {
            context.startActivity(
                Intent(context, SpectrogramActivity::class.java)
                    .putExtra(EXTRA_URI, song.uri.toString())
                    .putExtra(EXTRA_TITLE, song.title)
                    .putExtra(EXTRA_ARTIST, song.artist)
                    .putExtra(EXTRA_PATH, song.path)
                    .putExtra(EXTRA_FORMAT, song.format)
                    .putExtra(EXTRA_DURATION_MS, song.duration)
            )
        }
    }
}

data class SpectrogramRequest(
    val uri: Uri,
    val title: String,
    val artist: String,
    val path: String,
    val format: String,
    val durationMs: Long
)

/** Visible part of the spectrogram as fractions: time 0..1 of the duration, frequency 0..1 of the Nyquist frequency. */
private data class Viewport(
    val t0: Float = 0f,
    val t1: Float = 1f,
    val f0: Float = 0f,
    val f1: Float = 1f
) {
    val spanT: Float get() = t1 - t0
    val spanF: Float get() = f1 - f0
    val isFull: Boolean get() = t0 <= 0f && t1 >= 1f && f0 <= 0f && f1 >= 1f
}

private enum class SpecPalette(val labelRes: Int, val stops: IntArray) {
    SPECTRUM(
        R.string.spectrogram_palette_spectrum,
        intArrayOf(0xFF000000.toInt(), 0xFF1B0A4D.toInt(), 0xFF5B1A8C.toInt(), 0xFFB5265E.toInt(), 0xFFEB6A1A.toInt(), 0xFFF7C531.toInt(), 0xFFFFFFE0.toInt())
    ),
    BLUE(
        R.string.spectrogram_palette_blue,
        intArrayOf(0xFF000000.toInt(), 0xFF021A4A.toInt(), 0xFF0A4DB3.toInt(), 0xFF1E90FF.toInt(), 0xFF7FD4FF.toInt(), 0xFFFFFFFF.toInt())
    ),
    RED(
        R.string.spectrogram_palette_red,
        intArrayOf(0xFF000000.toInt(), 0xFF3A0504.toInt(), 0xFF8E0F0A.toInt(), 0xFFE02A12.toInt(), 0xFFFF8A2B.toInt(), 0xFFFFE9A8.toInt())
    ),
    MONO(
        R.string.spectrogram_palette_mono,
        intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
    );

    /** t in 0..1 (0 = quietest shown, 1 = loudest). */
    fun colorAt(t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = min(floor(x).toInt(), stops.size - 2)
        val f = x - i
        val a = stops[i]
        val b = stops[i + 1]
        fun ch(shift: Int): Int {
            val ca = (a ushr shift) and 0xFF
            val cb = (b ushr shift) and 0xFF
            return (ca + (cb - ca) * f).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

private sealed interface Analysis {
    data object Loading : Analysis
    data class Failed(val message: String) : Analysis
    data class Done(val result: SpectrogramAnalyzer.Result) : Analysis
}

private val MARGIN_LEFT = 52.dp
private val MARGIN_RIGHT = 62.dp
private val MARGIN_TOP = 8.dp
private val MARGIN_BOTTOM = 26.dp

@Composable
fun SpectrogramScreen(request: SpectrogramRequest, onBack: () -> Unit) {
    val context = LocalContext.current

    var fftSize by rememberSaveable { mutableIntStateOf(2048) }
    var plane by rememberSaveable { mutableIntStateOf(0) }
    var paletteIndex by rememberSaveable { mutableIntStateOf(0) }
    var rangeDb by rememberSaveable { mutableIntStateOf(100) }
    var progress by remember { mutableFloatStateOf(0f) }
    var analysis by remember { mutableStateOf<Analysis>(Analysis.Loading) }
    var viewport by remember { mutableStateOf(Viewport()) }
    var cross by remember { mutableStateOf<Offset?>(null) }

    val unsupportedText = stringResource(R.string.spectrogram_error)
    LaunchedEffect(fftSize) {
        analysis = Analysis.Loading
        progress = 0f
        viewport = Viewport()
        cross = null
        analysis = try {
            val r = withContext(Dispatchers.Default) {
                SpectrogramAnalyzer.analyze(
                    context = context,
                    uri = request.uri,
                    path = request.path,
                    format = request.format,
                    durationHintMs = request.durationMs,
                    fftSize = fftSize,
                    onProgress = { progress = it },
                    isActive = { isActive }
                )
            }
            if (r == null) Analysis.Loading else {
                if (plane >= r.planes.size) plane = 0
                Analysis.Done(r)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpectrogramAnalyzer.UnsupportedAudioException) {
            Analysis.Failed(e.message ?: unsupportedText)
        } catch (e: Throwable) {
            Analysis.Failed(unsupportedText)
        }
    }

    val palette = SpecPalette.entries[paletteIndex.coerceIn(0, SpecPalette.entries.size - 1)]
    val result = (analysis as? Analysis.Done)?.result

    val bitmap by produceState<Bitmap?>(null, result, plane, palette, rangeDb) {
        val r = result
        value = if (r == null) null else withContext(Dispatchers.Default) {
            renderBitmap(r, plane.coerceIn(0, r.planes.size - 1), palette, rangeDb)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.bounceClick()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = stringResource(R.string.spectrogram),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = listOf(request.title, request.artist).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (result != null) {
            val ch = when (result.channels) {
                1 -> stringResource(R.string.spectrogram_mono)
                2 -> stringResource(R.string.spectrogram_stereo)
                else -> stringResource(R.string.spectrogram_channels, result.channels)
            }
            Text(
                text = "${result.mime.removePrefix("audio/").uppercase(Locale.US)} • " +
                    "${formatKhz(result.sampleRate)} kHz • $ch • ${formatTime(result.durationSec)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            when (val a = analysis) {
                is Analysis.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) })
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.spectrogram_building, (progress * 100).roundToInt()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is Analysis.Failed -> Text(
                    text = a.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(32.dp)
                )
                is Analysis.Done -> SpectrogramPlot(
                    result = a.result,
                    bitmap = bitmap,
                    plane = plane.coerceIn(0, a.result.planes.size - 1),
                    palette = palette,
                    rangeDb = rangeDb,
                    viewport = viewport,
                    onViewport = { viewport = it },
                    cross = cross,
                    onCross = { cross = it },
                    onCyclePalette = { paletteIndex = (paletteIndex + 1) % SpecPalette.entries.size }
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 190.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (result != null && result.hasChannelPlanes) {
                ChipRow(title = stringResource(R.string.spectrogram_channel)) {
                    result.planes.indices.forEach { i ->
                        val label = when {
                            i == 0 && result.channels == 2 -> "L+R"
                            i == 0 -> stringResource(R.string.spectrogram_mix)
                            result.channels == 2 -> if (i == 1) "L" else "R"
                            else -> "Ch $i"
                        }
                        SpecChip(label, plane == i) { plane = i }
                    }
                }
            }
            ChipRow(title = stringResource(R.string.spectrogram_colors)) {
                SpecPalette.entries.forEachIndexed { i, p ->
                    SpecChip(stringResource(p.labelRes), paletteIndex == i) { paletteIndex = i }
                }
            }
            ChipRow(title = stringResource(R.string.spectrogram_range)) {
                listOf(60, 80, 100, 120).forEach { db ->
                    SpecChip("$db dB", rangeDb == db) { rangeDb = db }
                }
            }
            ChipRow(title = stringResource(R.string.spectrogram_resolution)) {
                listOf(1024, 2048, 4096).forEach { n ->
                    SpecChip("$n", fftSize == n) { if (fftSize != n) fftSize = n }
                }
                if (!viewport.isFull) {
                    SpecChip(stringResource(R.string.spectrogram_reset_zoom), false) { viewport = Viewport() }
                }
            }
            Text(
                text = stringResource(R.string.spectrogram_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ChipRow(title: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(74.dp)
        )
        content()
    }
}

@Composable
private fun SpecChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .bounceClick()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SpectrogramPlot(
    result: SpectrogramAnalyzer.Result,
    bitmap: Bitmap?,
    plane: Int,
    palette: SpecPalette,
    rangeDb: Int,
    viewport: Viewport,
    onViewport: (Viewport) -> Unit,
    cross: Offset?,
    onCross: (Offset?) -> Unit,
    onCyclePalette: () -> Unit
) {
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val panelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f).toArgb()

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val vpState = rememberUpdatedState(viewport)
    val onViewportState = rememberUpdatedState(onViewport)
    val onCrossState = rememberUpdatedState(onCross)
    val onCycleState = rememberUpdatedState(onCyclePalette)
    val sizeState = rememberUpdatedState(canvasSize)

    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val linePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val fillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    val duration = max(result.durationSec, 0.001f)
    val nyquist = result.sampleRate / 2f

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(result) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                val slop = viewConfiguration.touchSlop
                val minSpanT = (0.25f / duration).coerceIn(0.0005f, 1f)
                val minSpanF = 0.01f

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    var moved = false
                    var multi = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val rect = plotRect(sizeState.value, density)
                        val vp = vpState.value
                        if (pressed.size >= 2) {
                            multi = true
                            moved = true
                            val a = pressed[0]
                            val b = pressed[1]
                            val curDx = abs(a.position.x - b.position.x)
                            val curDy = abs(a.position.y - b.position.y)
                            val prevDx = abs(a.previousPosition.x - b.previousPosition.x)
                            val prevDy = abs(a.previousPosition.y - b.previousPosition.y)
                            val zx = if (curDx > 30f && prevDx > 30f) curDx / prevDx else 1f
                            val zy = if (curDy > 30f && prevDy > 30f) curDy / prevDy else 1f
                            val cx = (a.position.x + b.position.x) / 2f
                            val cy = (a.position.y + b.position.y) / 2f
                            val pcx = (a.previousPosition.x + b.previousPosition.x) / 2f
                            val pcy = (a.previousPosition.y + b.previousPosition.y) / 2f
                            val ax = ((pcx - rect.left) / rect.width).coerceIn(0f, 1f)
                            val ay = ((pcy - rect.top) / rect.height).coerceIn(0f, 1f)

                            val newSpanT = (vp.spanT / zx).coerceIn(minSpanT, 1f)
                            val tAnchor = vp.t0 + ax * vp.spanT
                            // The anchor follows the fingers' midpoint, so two-finger drags pan as well.
                            val nax = ((cx - rect.left) / rect.width).coerceIn(0f, 1f)
                            val nay = ((cy - rect.top) / rect.height).coerceIn(0f, 1f)
                            val t0 = (tAnchor - nax * newSpanT).coerceIn(0f, 1f - newSpanT)

                            val newSpanF = (vp.spanF / zy).coerceIn(minSpanF, 1f)
                            val fAnchor = vp.f1 - ay * vp.spanF
                            val f1 = (fAnchor + nay * newSpanF).coerceIn(newSpanF, 1f)

                            onViewportState.value(Viewport(t0, t0 + newSpanT, f1 - newSpanF, f1))
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1 && !multi) {
                            val c = pressed[0]
                            if (!moved && (c.position - startPos).getDistance() > slop) moved = true
                            if (moved) {
                                val dx = c.position.x - c.previousPosition.x
                                val dy = c.position.y - c.previousPosition.y
                                val dt = -dx / rect.width * vp.spanT
                                val df = dy / rect.height * vp.spanF
                                val t0 = (vp.t0 + dt).coerceIn(0f, 1f - vp.spanT)
                                val f0 = (vp.f0 + df).coerceIn(0f, 1f - vp.spanF)
                                onViewportState.value(Viewport(t0, t0 + vp.spanT, f0, f0 + vp.spanF))
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!moved && !multi) {
                        val now = down.uptimeMillis
                        if (now - lastTapTime < 300L && (startPos - lastTapPos).getDistance() < 48f * density) {
                            lastTapTime = 0L
                            onCycleState.value()
                        } else {
                            lastTapTime = now
                            lastTapPos = startPos
                            val rect = plotRect(sizeState.value, density)
                            val vp = vpState.value
                            if (rect.contains(startPos)) {
                                val t = vp.t0 + (startPos.x - rect.left) / rect.width * vp.spanT
                                val f = vp.f1 - (startPos.y - rect.top) / rect.height * vp.spanF
                                onCrossState.value(Offset(t, f))
                            } else {
                                onCrossState.value(null)
                            }
                        }
                    }
                }
            }
    ) {
        val d = density
        val rect = plotRect(IntSize(size.width.roundToInt(), size.height.roundToInt()), d)
        val pl = rect.left
        val pt = rect.top
        val pw = rect.width
        val ph = rect.height
        if (pw <= 0f || ph <= 0f) return@Canvas

        textPaint.textSize = 11f * d
        textPaint.color = onSurfaceVariant
        linePaint.strokeWidth = 1f * d

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            // Plot background and image
            fillPaint.color = 0xFF000000.toInt()
            nc.drawRect(pl, pt, pl + pw, pt + ph, fillPaint)
            if (bitmap != null) {
                val sx = viewport.t0 * bitmap.width
                val sw = viewport.spanT * bitmap.width
                val sy = (1f - viewport.f1) * bitmap.height
                val sh = viewport.spanF * bitmap.height
                val m = android.graphics.Matrix()
                m.postTranslate(-sx, -sy)
                m.postScale(pw / sw, ph / sh)
                m.postTranslate(pl, pt)
                nc.save()
                nc.clipRect(pl, pt, pl + pw, pt + ph)
                nc.drawBitmap(bitmap, m, bitmapPaint)
                nc.restore()
            }

            // Frequency axis (left)
            val fLo = viewport.f0 * nyquist
            val fHi = viewport.f1 * nyquist
            val fStep = niceStep(fHi - fLo, max(3, (ph / (48f * d)).toInt()))
            linePaint.color = (onSurfaceVariant and 0x00FFFFFF) or 0x33000000
            textPaint.textAlign = Paint.Align.RIGHT
            var v = ceil(fLo / fStep) * fStep
            while (v <= fHi + fStep * 0.001f) {
                val y = pt + ph * (1f - ((v / nyquist) - viewport.f0) / viewport.spanF)
                nc.drawLine(pl - 4f * d, y, pl, y, linePaint)
                val label = if (fHi >= 1000f) formatNumber(v / 1000f, fStep / 1000f) + "k" else formatNumber(v, fStep)
                nc.drawText(label, pl - 7f * d, y + 4f * d, textPaint)
                v += fStep
            }

            // Time axis (bottom)
            val tLo = viewport.t0 * duration
            val tHi = viewport.t1 * duration
            val tStep = niceStep(tHi - tLo, max(2, (pw / (70f * d)).toInt()))
            textPaint.textAlign = Paint.Align.CENTER
            var tv = ceil(tLo / tStep) * tStep
            while (tv <= tHi + tStep * 0.001f) {
                val x = pl + pw * ((tv / duration) - viewport.t0) / viewport.spanT
                nc.drawLine(x, pt + ph, x, pt + ph + 4f * d, linePaint)
                val label = if (duration >= 60f && tStep >= 1f) formatTime(tv) else formatNumber(tv, tStep) + " s"
                nc.drawText(label, x, pt + ph + 17f * d, textPaint)
                tv += tStep
            }

            // Level scale (right): the colour bar for the current range
            val barLeft = pl + pw + 14f * d
            val barWidth = 12f * d
            val steps = 64
            for (i in 0 until steps) {
                fillPaint.color = palette.colorAt(1f - (i + 0.5f) / steps)
                nc.drawRect(barLeft, pt + ph * i / steps, barLeft + barWidth, pt + ph * (i + 1) / steps + 1f, fillPaint)
            }
            textPaint.textAlign = Paint.Align.LEFT
            val dbStep = if (rangeDb <= 60) 10 else 20
            var db = 0
            while (db <= rangeDb) {
                val y = pt + ph * db / rangeDb
                nc.drawLine(barLeft + barWidth, y, barLeft + barWidth + 3f * d, y, linePaint)
                nc.drawText(if (db == 0) "0 dB" else "-$db", barLeft + barWidth + 5f * d, y + 4f * d, textPaint)
                db += dbStep
            }

            // Measuring cross-hair
            val cp = cross
            if (cp != null && cp.x in viewport.t0..viewport.t1 && cp.y in viewport.f0..viewport.f1) {
                val x = pl + pw * (cp.x - viewport.t0) / viewport.spanT
                val y = pt + ph * (1f - (cp.y - viewport.f0) / viewport.spanF)
                linePaint.color = accent
                nc.drawLine(x, pt, x, pt + ph, linePaint)
                nc.drawLine(pl, y, pl + pw, y, linePaint)

                val col = (cp.x * result.columns).toInt().coerceIn(0, result.columns - 1)
                val bin = (cp.y * result.bins).toInt().coerceIn(0, result.bins - 1)
                val q = result.planes[plane][col * result.bins + bin].toInt() and 0xFF
                val level = q / 255f * -SpectrogramAnalyzer.FLOOR_DB + SpectrogramAnalyzer.FLOOR_DB
                val hz = cp.y * nyquist
                val text = formatTimeFine(cp.x * duration) + "   " +
                    (if (hz >= 1000f) String.format(Locale.US, "%.2f kHz", hz / 1000f) else String.format(Locale.US, "%.0f Hz", hz)) +
                    "   " + String.format(Locale.US, "%.0f dB", level)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = onSurface
                val tw = textPaint.measureText(text)
                fillPaint.color = panelBg
                nc.drawRoundRect(pl + 8f * d, pt + 8f * d, pl + 24f * d + tw, pt + 32f * d, 8f * d, 8f * d, fillPaint)
                nc.drawText(text, pl + 16f * d, pt + 24f * d, textPaint)
            }
        }
    }
}

private fun plotRect(size: IntSize, density: Float): Rect = Rect(
    left = MARGIN_LEFT.value * density,
    top = MARGIN_TOP.value * density,
    right = size.width - MARGIN_RIGHT.value * density,
    bottom = size.height - MARGIN_BOTTOM.value * density
)

private fun renderBitmap(r: SpectrogramAnalyzer.Result, plane: Int, palette: SpecPalette, rangeDb: Int): Bitmap {
    val cols = r.columns
    val bins = r.bins
    val lut = IntArray(256) { q ->
        val db = q / 255f * -SpectrogramAnalyzer.FLOOR_DB + SpectrogramAnalyzer.FLOOR_DB
        palette.colorAt((db + rangeDb) / rangeDb)
    }
    val data = r.planes[plane]
    val pixels = IntArray(cols * bins)
    for (c in 0 until cols) {
        val base = c * bins
        for (b in 0 until bins) {
            // Row 0 is the highest frequency.
            pixels[(bins - 1 - b) * cols + c] = lut[data[base + b].toInt() and 0xFF]
        }
    }
    return Bitmap.createBitmap(pixels, cols, bins, Bitmap.Config.ARGB_8888)
}

private fun niceStep(range: Float, targetTicks: Int): Float {
    val raw = max(range, 1e-6f) / max(targetTicks, 1)
    val mag = 10f.pow(floor(log10(raw)))
    val n = raw / mag
    val m = when {
        n < 1.5f -> 1f
        n < 3.5f -> 2f
        n < 7.5f -> 5f
        else -> 10f
    }
    return m * mag
}

private fun formatNumber(v: Float, step: Float): String {
    val decimals = if (step >= 1f) 0 else ceil(-log10(step)).toInt().coerceIn(0, 3)
    return String.format(Locale.US, "%.${decimals}f", v)
}

private fun formatKhz(sampleRate: Int): String =
    if (sampleRate % 1000 == 0) (sampleRate / 1000).toString() else String.format(Locale.US, "%.1f", sampleRate / 1000f)

private fun formatTime(seconds: Float): String {
    val s = seconds.roundToInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun formatTimeFine(seconds: Float): String {
    val s = max(seconds, 0f)
    val m = (s / 60f).toInt()
    return String.format(Locale.US, "%d:%05.2f", m, s - m * 60f)
}
