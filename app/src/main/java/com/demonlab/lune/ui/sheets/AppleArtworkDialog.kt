package com.demonlab.lune.ui.sheets

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.demonlab.lune.R
import com.demonlab.lune.tools.AppleMusicArtworkClient
import com.demonlab.lune.ui.utils.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Search box + list of Apple Music albums. Tapping one downloads its large cover and hands the file to [onPicked].
 * The caller decides what to do with it (nothing is saved here). [initialQuery] is searched straight away when not blank.
 */
@Composable
fun AppleArtworkDialog(
    initialQuery: String,
    onPicked: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(initialQuery) }
    var busy by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<AppleMusicArtworkClient.Match>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        if (busy || query.isBlank()) return
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { AppleMusicArtworkClient.search(query) }
            busy = false
            matches = result.matches
            message = when (result.status) {
                AppleMusicArtworkClient.Status.FOUND -> null
                AppleMusicArtworkClient.Status.NOT_FOUND -> context.getString(R.string.apple_artwork_none)
                AppleMusicArtworkClient.Status.NETWORK_ERROR -> context.getString(R.string.apple_artwork_no_connection)
            }
        }
    }

    fun pick(match: AppleMusicArtworkClient.Match) {
        if (busy) return
        busy = true
        scope.launch {
            val file = withContext(Dispatchers.IO) { AppleMusicArtworkClient.downloadCover(context, match) }
            busy = false
            if (file != null) {
                Toast.makeText(context, context.getString(R.string.apple_artwork_added), Toast.LENGTH_LONG).show()
                onPicked(file)
            } else {
                Toast.makeText(context, context.getString(R.string.apple_artwork_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) runSearch() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.apple_artwork_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.apple_artwork_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    trailingIcon = {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { runSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.apple_artwork_search))
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(matches) { match ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !busy) { pick(match) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = match.thumbUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = match.album,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val details = listOfNotNull(match.artist.takeIf { it.isNotBlank() }, match.year?.toString())
                                    .joinToString(" · ")
                                if (details.isNotBlank()) {
                                    Text(
                                        text = details,
                                        style = MaterialTheme.typography.bodyMedium,
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/** The button the three cover screens use to open [AppleArtworkDialog]; colours come from the caller's sheet theme. */
@Composable
fun AppleArtworkButton(
    onClick: () -> Unit,
    container: Color,
    border: Color?,
    tint: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = border?.let { BorderStroke(1.dp, it) },
        modifier = modifier.fillMaxWidth().height(46.dp).bounceClick()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.apple_artwork_find),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
