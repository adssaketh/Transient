package com.demonlab.lune.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
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

/** True when the Aurora Glass palette and the glass look are both active (the full redesign). */
@Composable
fun rememberAuroraBackdrop(): Boolean {
    val st = SettingsManager.getInstance(LocalContext.current)
    return st.glassUiEnabled && st.useCustomColors && st.customColorPalette == 6 && !st.useAmoledPitchBlack
}

/** Indigo glow at the top-left and magenta glow at the bottom-right, behind every screen. */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val top = if (dark) Color(0xFF3A2FA0).copy(alpha = 0.65f) else Color(0xFFD9D0FF).copy(alpha = 0.8f)
    val bottom = if (dark) Color(0xFFB0306E).copy(alpha = 0.35f) else Color(0xFFFFB3D4).copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(top, Color.Transparent),
                        center = Offset(size.width * 0.2f, 0f),
                        radius = size.width * 1.15f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(bottom, Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height),
                        radius = size.width * 1.25f
                    )
                )
            }
    )
}

/** Rounded section chips (Resume, Mixes, All...) shown at the top of the home screen. */
@Composable
fun SectionChips(
    folders: List<String>,
    selected: String,
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(folders) { f ->
            val isSelected = f == selected
            Surface(
                onClick = { onSelect(f) },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f),
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.height(38.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelFor(f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
