package com.demonlab.lune.ui.screens.resume

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.demonlab.lune.tools.ArtistInfoRepository
import com.demonlab.lune.tools.ArtistNames
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.Song
import com.demonlab.lune.ui.components.SongCoverImage
import kotlinx.coroutines.launch

/**
 * The photo to show for [name]: one the user added, otherwise the online one (looked up in the background when the
 * setting is on). Null until something is known, so callers can show the album cover meanwhile.
 */
@Composable
fun rememberArtistPhoto(name: String): Any? {
    val context = LocalContext.current
    val repo = remember { ArtistInfoRepository.getInstance(context) }
    val settings = remember { SettingsManager.getInstance(context) }
    val version by repo.version.collectAsState()
    val online = settings.onlineArtistInfo
    androidx.compose.runtime.LaunchedEffect(name, online) {
        if (online) repo.fetch(name)
    }
    return remember(name, version) { repo.manualPhoto(name) ?: repo.cached(name)?.imageUrl }
}

/** Photo + short bio + the songs of this artist in the library, in one bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistInfoSheet(
    artistName: String,
    allSongs: List<Song>,
    onDismiss: () -> Unit,
    onSongClick: (Song, List<Song>) -> Unit,
    onOpenArtistPage: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val repo = remember { ArtistInfoRepository.getInstance(context) }
    val settings = remember { SettingsManager.getInstance(context) }
    val scope = rememberCoroutineScope()
    val online = settings.onlineArtistInfo
    val version by repo.version.collectAsState()

    val songs = remember(allSongs, artistName) {
        allSongs.filter { ArtistNames.sings(it.artist, artistName) }.sortedBy { it.title.lowercase() }
    }
    val coverSong = remember(songs) { songs.firstOrNull { it.coverUrl != null || it.albumArtUri != null } ?: songs.firstOrNull() }

    var loading by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(artistName, online) {
        if (online) {
            loading = true
            repo.fetch(artistName)
            loading = false
        }
    }
    val info = remember(version, artistName) { repo.cached(artistName) }
    val manual = remember(version, artistName) { repo.manualPhoto(artistName) }
    val photo: Any? = manual ?: info?.imageUrl

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch { repo.setManualPhoto(artistName, uri) }
    }

    if (showNameDialog) {
        var typed by remember { mutableStateOf(repo.alias(artistName) ?: artistName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Search by another name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Type the name this artist is known by, for example \"S. P. Balasubrahmanyam\" for \"SP Balu\". Your library keeps the name it has now.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNameDialog = false
                    scope.launch {
                        loading = true
                        repo.setAlias(artistName, typed)
                        loading = false
                    }
                }) { Text("Search") }
            },
            dismissButton = {
                Row {
                    if (repo.alias(artistName) != null) {
                        TextButton(onClick = {
                            showNameDialog = false
                            scope.launch {
                                loading = true
                                repo.clearAlias(artistName)
                                loading = false
                            }
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(132.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { picker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        // The album cover is the base; the real photo fades in over it once it is there.
                        if (coverSong != null) {
                            SongCoverImage(
                                coverUrl = coverSong.coverUrl ?: coverSong.albumArtUri ?: coverSong.uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(132.dp),
                                shape = CircleShape,
                                iconScale = 0.5f
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(56.dp))
                        }
                        if (photo != null) {
                            AsyncImage(
                                model = photo,
                                contentDescription = artistName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(132.dp).clip(CircleShape)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = artistName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (info?.title != null && !info.title.equals(artistName, ignoreCase = true)) "${info.title} \u00B7 ${songs.size} songs in your library" else "${songs.size} songs in your library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { if (songs.isNotEmpty()) onSongClick(songs.first(), songs) },
                            enabled = songs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play all")
                        }
                        FilledTonalButton(
                            onClick = {
                                val mixed = songs.shuffled()
                                if (mixed.isNotEmpty()) onSongClick(mixed.first(), mixed)
                            },
                            enabled = songs.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (manual != null) "Change photo" else "Add photo")
                        }
                        if (manual != null) {
                            TextButton(onClick = { repo.clearManualPhoto(artistName) }, modifier = Modifier.weight(1f)) {
                                Text("Remove my photo")
                            }
                        } else if (onOpenArtistPage != null) {
                            TextButton(
                                onClick = {
                                    onDismiss()
                                    onOpenArtistPage(artistName)
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Open artist page") }
                        }
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "About",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (online) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            loading = true
                                            repo.fetch(artistName, force = true)
                                            loading = false
                                        }
                                    },
                                    enabled = !loading
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Refresh")
                                }
                            }
                        }
                        when {
                            !online -> Text(
                                text = "Online artist info is turned off. Turn on \"Artist photos & info from the internet\" in Customization to see a short bio and a photo. You can still add your own photo above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            info?.bio != null -> {
                                Text(
                                    text = info.bio,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (info.source != null) {
                                    val page = info.pageUrl
                                    Text(
                                        text = if (page != null) "Source: ${info.source} \u2022 Read more" else "Source: ${info.source}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable(enabled = page != null) {
                                            page?.let { link ->
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                                } catch (_: Exception) { }
                                            }
                                        }
                                    )
                                }
                            }
                            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Looking up information\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> Text(
                                text = "No information found for this name (or no connection). Try another spelling below, or add your own photo above.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (online) {
                            TextButton(onClick = { showNameDialog = true }) {
                                Text(
                                    if (repo.alias(artistName) != null) "Searching as \"${repo.alias(artistName)}\" \u2022 change"
                                    else "Wrong person or nothing found? Search by another name"
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Songs in your library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            items(songs, key = { it.id }) { song ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSongClick(song, songs) }
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    SongCoverImage(
                        coverUrl = song.coverUrl ?: song.uri,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        iconScale = 0.6f
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOf(song.album, song.artist).filter { it.isNotBlank() }.joinToString(" \u00B7 "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
