package com.demonlab.lune.ui.data

import com.demonlab.lune.ui.components.FastScrollbarGrid
import com.demonlab.lune.ui.utils.pinchToChangeColumns
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import com.demonlab.lune.ui.utils.bounceClick
import com.demonlab.lune.ui.utils.fastSmoothScrollToItem
import com.demonlab.lune.R
import com.demonlab.lune.tools.Song
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.SongCoverImage
import com.demonlab.lune.ui.components.ScrollToTopPill
import com.demonlab.lune.ui.components.rememberScrollToTopVisibility
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

data class Album(
    val id: Long,
    val name: String,
    val artist: String,
    val albumArtUri: Uri?,
    val coverUrl: String?,
    val songs: List<Song>
)

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Album) -> Unit,
    bottomPadding: Dp,
    hasBlurBackground: Boolean = false,
    isDarkTheme: Boolean = false,
    customActiveColor: Color? = null,
    activePlaylistId: Long? = null,
    indexLetter: (Album) -> String = { it.name }
) {
    val initialIndex = remember(activePlaylistId, albums) {
        if (activePlaylistId != null) {
            val idx = albums.indexOfFirst { it.id == activePlaylistId }
            if (idx >= 0) idx else 0
        } else 0
    }
    val gridState = rememberLazyGridState()
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val isScrollToTopVisible = rememberScrollToTopVisibility(gridState, settingsManager.isScrollToTopEnabled)
    var columns by remember { mutableIntStateOf(settingsManager.albumGridColumns) }

    LaunchedEffect(activePlaylistId) {
        if (initialIndex > 0) {
            gridState.scrollToItem(initialIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .pinchToChangeColumns(columns = columns, minColumns = 1, maxColumns = 5) {
                    columns = it
                    settingsManager.albumGridColumns = it
                },
            contentPadding = PaddingValues(start = 16.dp, end = if (settingsManager.isAlphabetIndexerEnabled) 34.dp else 16.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                albums,
                key = { _, album -> album.id },
                contentType = { _, _ -> "album_card" }
            ) { index, album ->
                val isFirst = index == 0
                val isLast = index == albums.lastIndex
                val isPlaying = album.id == activePlaylistId
                Box(modifier = Modifier.animateItem()) {
                    AlbumCard(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        hasBlurBackground = hasBlurBackground,
                        isPlaying = isPlaying
                    )
                }
            }
        }

        if (settingsManager.isAlphabetIndexerEnabled) {
            FastScrollbarGrid(
                gridState = gridState,
                items = albums,
                itemKeyOrLetter = indexLetter,
                hasBlurBackground = hasBlurBackground,
                accentColor = customActiveColor,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(bottom = bottomPadding)
            )
        }

        ScrollToTopPill(
            visible = isScrollToTopVisible.value,
            onClick = {
                scope.launch {
                    gridState.fastSmoothScrollToItem(0)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPadding + 14.dp),
            hasBlurBackground = hasBlurBackground,
            isDarkTheme = isDarkTheme,
            customActiveColor = customActiveColor
        )
    }
}

@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    hasBlurBackground: Boolean = false,
    isPlaying: Boolean = false
) {
    // Corner radius is a share of the tile width so a small tile looks like a scaled-down player cover
    // (a fixed 24dp turned 4-column tiles into blobby squircles).
    val cardShape = RoundedCornerShape(topStartPercent = 8, topEndPercent = 8, bottomStartPercent = 3, bottomEndPercent = 3)
    val coverShape = RoundedCornerShape(10.dp)
    val showYear = SettingsManager.getInstance(LocalContext.current).showAlbumYear
    val albumSubtitle = remember(album, showYear) { albumSubtitleWithYear(album, showYear) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Text follows the tile: the smaller the album tile (pinch to more columns), the smaller its title and artist.
        val tileWidth = maxWidth.value
        val titleSp = (3.9f + 0.0866f * tileWidth).coerceIn(9f, 22f)
        val subtitleSp = (titleSp * 0.8f).coerceIn(8f, 16f)
        val gap = (tileWidth * 0.045f).coerceIn(3f, 9f).dp
        val sidePadding = (tileWidth * 0.04f).coerceIn(2f, 8f).dp
        val dot = (titleSp * 0.5f).coerceIn(4f, 9f).dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bounceClick()
                .clip(cardShape)
                .clickable(onClick = onClick)
        ) {
            Box {
                Surface(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .fillMaxWidth(),
                    shape = coverShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    SongCoverImage(
                        coverUrl = album.coverUrl ?: album.albumArtUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        shape = coverShape,
                        iconScale = 0.68f
                    )
                }
            }
            Spacer(modifier = Modifier.height(gap))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = sidePadding)
            ) {
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .size(dot)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(dot * 0.75f))
                }
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = titleSp.sp,
                        lineHeight = (titleSp * 1.25f).sp
                    ),
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                    color = if (hasBlurBackground) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = albumSubtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = subtitleSp.sp,
                    lineHeight = (subtitleSp * 1.25f).sp
                ),
                maxLines = 1,
                color = if (hasBlurBackground) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = sidePadding, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(gap / 2))
        }
    }
}

/** First known release year among an album's songs, or 0. */
fun albumYear(album: Album): Int = album.songs.firstOrNull { it.year > 0 }?.year ?: 0

/** "Artist • 2011" when the year is known, wanted, and not already part of the album title. */
fun albumSubtitleWithYear(album: Album, showYear: Boolean): String {
    val year = albumYear(album)
    return if (showYear && year > 0 && album.artist.isNotEmpty() && !album.name.contains(year.toString())) {
        "${album.artist} \u2022 $year"
    } else {
        album.artist
    }
}
