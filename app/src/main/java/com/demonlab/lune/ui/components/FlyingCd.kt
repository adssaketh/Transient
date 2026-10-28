package com.demonlab.lune.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.demonlab.lune.tools.DiagnosticLogger
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "flying CD": when an album is tapped, a CD slides out of its cover, arcs across the screen and lands on the
 * mini-player's CD.
 *
 * How it stays smooth:
 *  - one Animatable (0..1) drives everything; all movement is read inside graphicsLayer / draw lambdas, so nothing
 *    recomposes while the CD is in the air (composition only runs when a flight starts or ends);
 *  - the cover image is already in Coil's memory cache (the tile just showed it), so there is no decoding at the start;
 *  - one continuous curve and one easing, no phase switches that could look like a hiccup;
 *  - the real mini-player CD is hidden during the flight and cross-faded in as the flying one lands.
 */
object FlyingCd {
    /** Mini-player CD centre and diameter in root pixels. Plain fields written by layout callbacks (no recomposition). */
    @Volatile var targetCenter: Offset? = null
    @Volatile var targetDiameter: Float = 0f

    /** The tapped album cover, recorded by the album tile right before the tap is handled. */
    @Volatile var pendingBounds: Rect? = null
    @Volatile var pendingCornerPx: Float = 0f
    @Volatile var pendingModel: Any? = null

    /** 0 hides the real mini-player CD while the flying one is in the air. Read only inside graphicsLayer. */
    val miniAlpha = mutableFloatStateOf(1f)

    /** Delays (ms after the tap) so the heavy work does not compete with the first frames of the flight. */
    const val PLAY_DELAY_MS = 350L
    const val DETAIL_DELAY_MS = 160L
    const val DURATION_MS = 820

    class Flight(
        val source: Rect,
        val cornerPx: Float,
        val model: Any?,
        val p0: Offset,
        val pc: Offset,
        val p3: Offset,
        val startDiameter: Float,
        val endDiameter: Float
    ) {
        /** Point on the curve (quadratic Bezier) for eased progress [e] in 0..1. */
        fun at(e: Float): Offset {
            val u = 1f - e
            return p0 * (u * u) + pc * (2f * u * e) + p3 * (e * e)
        }
    }

    var flight by mutableStateOf<Flight?>(null)
        private set
    val progress = Animatable(0f)

    private val easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    fun ease(t: Float): Float = easing.transform(t)

    private var job: Job? = null

    /**
     * Starts the flight from the tile recorded in [pendingBounds]. Returns false, and does nothing, when it cannot run
     * (system animations off, no recorded tile, mini player not on screen). The caller then just carries on without it.
     */
    fun start(scope: CoroutineScope, animationsEnabled: Boolean): Boolean {
        val src = pendingBounds
        val model = pendingModel
        val target = targetCenter
        val endD = targetDiameter
        pendingBounds = null
        if (!animationsEnabled) { DiagnosticLogger.note("FLYCD skipped: system animations are off"); return false }
        if (src == null || src.width <= 0f) { DiagnosticLogger.note("FLYCD skipped: tile position unknown"); return false }
        if (target == null || endD <= 0f) { DiagnosticLogger.note("FLYCD skipped: mini player not on screen"); return false }
        val p0 = src.center
        val dist = (target - p0).getDistance()
        if (dist < 1f) return false
        val dir = (target - p0) / dist
        // Bow the path to the upper side, like a thrown disc.
        var perp = Offset(dir.y, -dir.x)
        if (perp.y > 0f) perp = Offset(-perp.x, -perp.y)
        val startD = src.width * 0.92f
        val bulge = (dist * 0.28f).coerceAtLeast(startD * 0.8f)
        val pc = (p0 + target) / 2f + perp * bulge

        flight = Flight(src, pendingCornerPx, model, p0, pc, target, startD, endD)
        job?.cancel()
        job = scope.launch {
            try {
                miniAlpha.floatValue = 0f
                progress.snapTo(0f)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = DURATION_MS, easing = LinearEasing),
                    block = { miniAlpha.floatValue = ((value - 0.86f) / 0.14f).coerceIn(0f, 1f) }
                )
            } finally {
                flight = null
                miniAlpha.floatValue = 1f
            }
        }
        DiagnosticLogger.note("FLYCD started (distance=${dist.toInt()}px)")
        return true
    }
}

/** Draws the flying CD above everything else. Put it last inside a full-screen Box. It never blocks touches. */
@Composable
fun FlyingCdOverlay() {
    val flight = FlyingCd.flight
    if (flight != null) FlyingCdLayer(flight)
}

@Composable
private fun FlyingCdLayer(flight: FlyingCd.Flight) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val baseDp = with(density) { flight.startDiameter.toDp() }
    val shadowPx = with(density) { 10.dp.toPx() }
    val endScale = flight.endDiameter / flight.startDiameter

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .drawWithContent {
                // While the CD still overlaps its album cover it is "inside the sleeve": hide the overlapping part.
                val t = FlyingCd.progress.value
                val centre = flight.at(FlyingCd.ease(t))
                val overlapping = t < 0.5f &&
                    (centre - flight.source.center).getDistance() < flight.source.width / 2f + flight.startDiameter / 2f
                if (overlapping) {
                    val hole = Path().apply {
                        addRoundRect(RoundRect(flight.source.translate(-origin), CornerRadius(flight.cornerPx)))
                    }
                    clipPath(hole, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
                } else {
                    drawContent()
                }
            }
    ) {
        Box(
            modifier = Modifier
                .size(baseDp)
                .graphicsLayer {
                    val t = FlyingCd.progress.value
                    val e = FlyingCd.ease(t)
                    val c = flight.at(e) - origin
                    val bump = if (t > 0.8f) 1f + 0.05f * sin(PI.toFloat() * ((t - 0.8f) / 0.2f)) else 1f
                    val s = lerp(1f, endScale, e) * bump
                    translationX = c.x - size.width / 2f
                    translationY = c.y - size.height / 2f
                    scaleX = s
                    scaleY = s
                    rotationZ = 540f * (1f - (1f - t).pow(3))
                    alpha = 1f - ((t - 0.86f) / 0.14f).coerceIn(0f, 1f)
                    shadowElevation = shadowPx * sin(PI.toFloat() * t)
                    shape = CircleShape
                    clip = false
                }
                .clip(CircleShape)
        ) {
            SongCoverImage(
                coverUrl = flight.model,
                shape = CircleShape,
                modifier = Modifier.fillMaxSize(),
                iconScale = 0.68f
            )
            CdCenterHole()
        }
    }
}
