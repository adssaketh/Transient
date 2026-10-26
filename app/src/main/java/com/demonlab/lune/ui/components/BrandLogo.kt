package com.demonlab.lune.ui.components

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.demonlab.lune.R

/**
 * The Transient mark, recoloured with the app's own palette (primary -> tertiary) instead of the fixed cyan / magenta of
 * the artwork, so it always matches the current theme. The artwork's light and shadow are kept; only the colour changes.
 * With [pulsing] it gently "breathes", which the header uses while music is playing.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    pulsing: Boolean = false
) {
    val transition = rememberInfiniteTransition(label = "BrandLogoPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BrandLogoScale"
    )
    val glyph = ImageBitmap.imageResource(id = R.drawable.ic_brand_glyph)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (pulsing) pulse else 1f
                scaleX = s
                scaleY = s
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        val target = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        // 1) the artwork without its colour, 2) the theme gradient blended over it, 3) cut back to the artwork's shape
        drawImage(glyph, dstSize = target, colorFilter = grey)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(primary, tertiary),
                start = Offset(0f, 0f),
                end = Offset(this.size.width, this.size.height)
            ),
            blendMode = BlendMode.Overlay
        )
        drawImage(glyph, dstSize = target, blendMode = BlendMode.DstIn)
    }
}

/**
 * The "Transient" lettering as an image, tinted with [color] so it follows the theme (light/dark/blur).
 * Use it wherever the app name stands alone as a title; keep plain text inside sentences.
 */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Image(
        painter = painterResource(id = R.drawable.ic_brand_wordmark),
        contentDescription = stringResource(R.string.app_name),
        colorFilter = ColorFilter.tint(color),
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        modifier = modifier.height(height)
    )
}
