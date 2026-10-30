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
 * The Transient mark: a jewel case with its black ribbed spine, a cover and a disc. The cover takes the app's primary
 * colour, which follows the album art that is playing, and the disc is a darker shade of the same colour, so the mark
 * always matches the theme. With [pulsing] it gently "breathes", which the header uses while music is playing.
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
    val cover = MaterialTheme.colorScheme.primary
    val disc = androidx.compose.ui.graphics.lerp(cover, Color.Black, 0.78f)
    Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (pulsing) pulse else 1f
                scaleX = s
                scaleY = s
            }
    ) {
        val u = this.size.width / 100f   // the drawing is laid out on a 100 x 100 grid
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        fun box(w: Float, h: Float) = androidx.compose.ui.geometry.Size(w * u, h * u)
        fun corner(r: Float) = androidx.compose.ui.geometry.CornerRadius(r * u, r * u)

        // case: dark body with a light plastic edge
        drawRoundRect(Color(0xFF2B2B2B), at(14f, 16f), box(72f, 68f), corner(8f))
        drawRoundRect(
            Color(0xFFD6D3D1), at(14f, 16f), box(72f, 68f), corner(8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f * u)
        )
        // spine with its ribs
        drawRoundRect(Color(0xFF0B0B0B), at(18f, 20f), box(14f, 60f), corner(3f))
        for (x in floatArrayOf(21f, 24f, 27f, 30f)) {
            drawLine(Color(0xFF3A3A3A), at(x, 22f), at(x, 78f), strokeWidth = 1.2f * u)
        }
        // cover, disc and hole
        drawRoundRect(cover, at(36f, 20f), box(46f, 60f), corner(4f))
        drawCircle(disc, radius = 15f * u, center = at(59f, 46f))
        drawCircle(cover, radius = 4f * u, center = at(59f, 46f))
        // sound wave along the bottom of the cover
        val wave = androidx.compose.ui.graphics.Path().apply {
            moveTo(42f * u, 68f * u)
            quadraticBezierTo(47f * u, 60f * u, 52f * u, 68f * u)
            quadraticBezierTo(57f * u, 76f * u, 62f * u, 68f * u)
            quadraticBezierTo(67f * u, 60f * u, 72f * u, 68f * u)
        }
        drawPath(
            wave, Color.White.copy(alpha = 0.92f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.6f * u,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
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
