package com.demonlab.lune.ui.components

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
