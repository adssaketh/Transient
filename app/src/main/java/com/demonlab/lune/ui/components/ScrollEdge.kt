package com.demonlab.lune.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.demonlab.lune.tools.SettingsManager

/**
 * Scroll edge effect, as in the Ping app: content fades out where it slides under the bars (the top edge of the list
 * and the bottom pills / mini player), optionally with a soft blur that is strongest right next to the edge.
 *
 * Settings (Customization): effect Off / Fade / Fade + blur, blur strength Light / Medium / Strong and fade height
 * Short / Medium / Tall. [topBar] and [bottomBar] are the heights of whatever floats over the list at each end.
 * The blur needs Android 12+; on older versions "Fade + blur" simply fades.
 */
@Composable
fun Modifier.scrollEdgeEffect(topBar: Dp = 0.dp, bottomBar: Dp = 0.dp): Modifier {
    val settings = SettingsManager.getInstance(LocalContext.current)
    val effect = settings.scrollEdgeEffect
    if (effect == 0) return this

    val density = LocalDensity.current
    val extraPx = with(density) { (when (settings.edgeFadeHeight) { 0 -> 12.dp; 2 -> 44.dp; else -> 24.dp }).toPx() }
    val blurPx = with(density) { (when (settings.edgeBlurLevel) { 0 -> 6.dp; 2 -> 16.dp; else -> 10.dp }).toPx() }
    val topPx = with(density) { topBar.toPx() }
    val bottomPx = with(density) { bottomBar.toPx() }
    val blurAvailable = effect == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val contentLayer = rememberGraphicsLayer()
    val fadedLayer = rememberGraphicsLayer()
    val softLayer = rememberGraphicsLayer()
    val strongLayer = rememberGraphicsLayer()

    return this.drawWithContent {
        val h = size.height
        if (h <= 0f) {
            drawContent()
            return@drawWithContent
        }
        // Bars here are translucent glass, so the fade starts at the very edge of the screen (like Ping's glass bars).
        val topFull = (topPx * 0.8f + extraPx * 0.6f).coerceIn(0f, h * 0.4f)
        val botFull = (bottomPx * 0.8f + extraPx * 0.6f).coerceIn(0f, h * 0.4f)

        val fadeBrush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                (topFull / h) to Color.Black,
                ((h - botFull) / h) to Color.Black,
                1f to Color.Transparent
            )
        )

        contentLayer.record { this@drawWithContent.drawContent() }

        if (!blurAvailable) {
            drawMasked(fadeBrush) { drawLayer(contentLayer) }
            return@drawWithContent
        }

        // The faded list, blurred twice (soft + strong) and laid back over itself only near the edges.
        fadedLayer.record { drawMasked(fadeBrush) { drawLayer(contentLayer) } }
        val softPx = maxOf(2f, blurPx * 0.45f)
        softLayer.renderEffect = BlurEffect(softPx, softPx, TileMode.Clamp)
        softLayer.record { drawLayer(fadedLayer) }
        strongLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
        strongLayer.record { drawLayer(fadedLayer) }

        fun strip(topSolid: Float, topEnd: Float, botSolid: Float, botEnd: Float) = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black,
                (topSolid / h) to Color.Black,
                (topEnd / h) to Color.Transparent,
                ((h - botEnd) / h) to Color.Transparent,
                ((h - botSolid) / h) to Color.Black,
                1f to Color.Black
            )
        )

        drawLayer(fadedLayer)
        drawMasked(strip(topFull * 0.15f, topFull, botFull * 0.15f, botFull)) { drawLayer(softLayer) }
        drawMasked(strip(0f, topFull * 0.7f, 0f, botFull * 0.7f)) { drawLayer(strongLayer) }
    }
}

/** Draws [draw] and keeps only the part where [brush] is opaque (brush alpha acts as the mask). */
private fun DrawScope.drawMasked(brush: Brush, draw: DrawScope.() -> Unit) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
        draw()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        canvas.restore()
    }
}
