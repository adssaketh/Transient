package com.demonlab.lune.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.demonlab.lune.tools.SettingsManager

/** True when the "Glass UI" setting is on (Customisation settings). */
@Composable
fun rememberGlassUi(): Boolean = SettingsManager.getInstance(LocalContext.current).glassUiEnabled

/** Thin light edge that fades diagonally, like light catching the rim of frosted glass. */
fun glassBorder(): BorderStroke = BorderStroke(
    1.dp,
    Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = 0.40f),
            Color.White.copy(alpha = 0.06f),
            Color.White.copy(alpha = 0.18f)
        )
    )
)

/** Soft top-to-bottom sheen laid over a translucent surface. */
fun Modifier.glassSheen(shape: Shape): Modifier = background(
    Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.02f))
    ),
    shape
)
