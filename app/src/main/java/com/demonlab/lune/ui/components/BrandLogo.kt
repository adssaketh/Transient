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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import com.demonlab.lune.R

/**
 * The Transient mark: a 3D jewel case with its black ribbed spine, a cover and a disc. It is stacked from five pictures
 * (case, cover, light and shade, disc, wave). The cover and the disc are grey maps that are multiplied by the cover
 * colour, which follows the album art that is playing (the app's primary colour), so the mark always matches the theme;
 * the case, spine, light and wave stay as they are. With [pulsing] it gently "breathes", which the header uses while
 * music is playing.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 60.dp,
    pulsing: Boolean = false,
    coverColor: Color? = null
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
    val cover = (coverColor ?: MaterialTheme.colorScheme.primary).copy(alpha = 1f)
    val caseImage = ImageBitmap.imageResource(R.drawable.logo3d_base)
    val coverImage = ImageBitmap.imageResource(R.drawable.logo3d_cover)
    val shadeImage = ImageBitmap.imageResource(R.drawable.logo3d_overlay)
    val discImage = ImageBitmap.imageResource(R.drawable.logo3d_disc)
    val waveImage = ImageBitmap.imageResource(R.drawable.logo3d_wave)
    val tint = remember(cover) { ColorFilter.tint(cover, BlendMode.Modulate) }
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
        // every picture covers the grid from 6 to 94 in both directions (88 units)
        val offset = IntOffset((6f * u).roundToInt(), (6f * u).roundToInt())
        val side = (88f * u).roundToInt().coerceAtLeast(1)
        val dst = IntSize(side, side)
        fun layer(image: ImageBitmap, filter: ColorFilter? = null) {
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = offset,
                dstSize = dst,
                colorFilter = filter,
                filterQuality = FilterQuality.High
            )
        }
        layer(caseImage)
        layer(coverImage, tint)
        layer(shadeImage)
        layer(discImage, tint)
        layer(waveImage)
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
