package com.demonlab.lune.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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


/** Floating glass pill with four round icon buttons (Home, Search, Library, Equalizer), as in the Aurora mockup. */
@Composable
fun AuroraNavPill(
    homeSelected: Boolean,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        border = glassBorder(),
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuroraNavItem(Icons.Rounded.Home, "Home", homeSelected, onHome)
            AuroraNavItem(Icons.Rounded.Search, "Search", false, onSearch)
            AuroraNavItem(Icons.Rounded.LibraryMusic, "Library", !homeSelected, onLibrary)
            AuroraNavItem(Icons.Rounded.Equalizer, "Equalizer", false, onEqualizer)
        }
    }
}

@Composable
private fun AuroraNavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.size(24.dp)
        )
    }
}


/** "676 songs" pill plus a round A-Z sort button, shown above the section chips. */
@Composable
fun AuroraStatsRow(
    songCount: Int,
    onSortClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = fill,
            border = glassBorder(),
            modifier = Modifier.height(44.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "$songCount songs",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        Surface(
            onClick = onSortClick,
            shape = CircleShape,
            color = fill,
            border = glassBorder(),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "A\u2013Z",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
