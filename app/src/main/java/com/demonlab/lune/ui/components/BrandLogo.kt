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
import com.demonlab.lune.R

/**
 * The Transient mark (full-colour play + note symbol).
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
    Image(
        painter = painterResource(id = R.drawable.ic_brand_glyph),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (pulsing) pulse else 1f
                scaleX = s
                scaleY = s
            }
    )
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
