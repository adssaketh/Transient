package com.demonlab.lune.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.AuroraBackdrop
import com.demonlab.lune.ui.components.BrandLogo
import com.demonlab.lune.ui.components.BrandWordmark
import com.demonlab.lune.ui.components.PaletteOption
import com.demonlab.lune.ui.components.PaletteSwatchRow
import com.demonlab.lune.ui.components.glassBorder

private const val PAGE_LOOK = 0
private const val PAGE_ACCESS = 1
private const val PAGE_LIBRARY = 2
private const val PAGE_COUNT = 3

/**
 * First-run setup in three short, scrollable pages:
 *  1. look: theme (auto / light / dark) and colour palette, applied live,
 *  2. access: every permission on one page, with "Allow all" asking for them one after the other,
 *  3. library: the music folder and whether to show every folder.
 * Nothing here is forced: every step can be skipped and changed later in Settings.
 */
@Composable
fun OnboardingScreen(
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    palette: Int,
    onPaletteChange: (Int) -> Unit,
    onStartClick: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { SettingsManager.getInstance(context) }

    fun has(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val musicPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val needsBluetooth = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    var musicGranted by remember { mutableStateOf(has(musicPermission)) }
    var notificationsGranted by remember { mutableStateOf(!needsNotifications || has(Manifest.permission.POST_NOTIFICATIONS)) }
    var bluetoothGranted by remember { mutableStateOf(!needsBluetooth || has(Manifest.permission.BLUETOOTH_CONNECT)) }
    var folderGranted by remember { mutableStateOf(settings.musicFolderUri != null) }
    var showAllFolders by remember { mutableStateOf(settings.showAllFoldersOnStart) }

    fun refreshPermissions() {
        musicGranted = has(musicPermission)
        notificationsGranted = !needsNotifications || has(Manifest.permission.POST_NOTIFICATIONS)
        bluetoothGranted = !needsBluetooth || has(Manifest.permission.BLUETOOTH_CONNECT)
    }

    // One launcher for both "Allow all" (the system then shows the dialogs one after the other) and single rows.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }
    val missingPermissions = buildList {
        if (!musicGranted) add(musicPermission)
        if (!notificationsGranted) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!bluetoothGranted) add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val allPermissionsGranted = missingPermissions.isEmpty()

    val initialFolderUri = remember {
        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (musicDir.exists()) {
                DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:${musicDir.name}")
            } else null
        } catch (_: Exception) { null }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                settings.musicFolderUri = uri.toString()
                settings.isInitialFolderScanPending = true
                folderGranted = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    var page by rememberSaveable { mutableIntStateOf(PAGE_LOOK) }
    BackHandler(enabled = page > PAGE_LOOK) { page-- }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        AuroraBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            StepDots(count = PAGE_COUNT, current = page)

            AnimatedContent(
                targetState = page,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it / 4 } + fadeIn(tween(250)))
                            .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally { -it / 4 } + fadeIn(tween(250)))
                            .togetherWith(slideOutHorizontally { it / 4 } + fadeOut(tween(200)))
                    }
                },
                label = "setupPage"
            ) { current ->
                // Every page scrolls and starts at the top, so nothing is ever cut off on a small screen
                // and nothing floats in the middle with empty bands above and below.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (current) {
                        PAGE_LOOK -> {
                            Header(
                                title = "Make it yours",
                                subtitle = "Pick how Transient looks. You can change this any time in Settings."
                            )
                            SetupCard(title = "Theme") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    ThemeChoice(Icons.Default.BrightnessAuto, "Auto", themeMode == 0, Modifier.weight(1f)) { onThemeModeChange(0) }
                                    ThemeChoice(Icons.Default.LightMode, "Light", themeMode == 1, Modifier.weight(1f)) { onThemeModeChange(1) }
                                    ThemeChoice(Icons.Default.DarkMode, "Dark", themeMode == 2, Modifier.weight(1f)) { onThemeModeChange(2) }
                                }
                            }
                            SetupCard(title = "Colours") {
                                PaletteSwatchRow(
                                    options = listOf(
                                        PaletteOption(6, "Aurora", Color(0xFF8B7BFF)),
                                        PaletteOption(0, "Violet", Color(0xFF6650A4)),
                                        PaletteOption(1, "Coral", Color(0xFFB04B38)),
                                        PaletteOption(2, "Sage", Color(0xFF386B52)),
                                        PaletteOption(3, "Ocean", Color(0xFF2E6580)),
                                        PaletteOption(4, "Lavender", Color(0xFF6E568F)),
                                        PaletteOption(5, "Amber", Color(0xFF7F5700))
                                    ),
                                    selectedIndex = palette,
                                    onSelect = onPaletteChange
                                )
                            }
                        }

                        PAGE_ACCESS -> {
                            Header(
                                title = "Allow access",
                                subtitle = "Tap Allow all and answer the prompts one after the other, or choose them one by one."
                            )
                            SetupCard {
                                PermissionRow(
                                    icon = Icons.Default.MusicNote,
                                    title = "Music & audio",
                                    description = "Find and play the music on this device.",
                                    granted = musicGranted,
                                    onAllow = { permissionLauncher.launch(arrayOf(musicPermission)) }
                                )
                                if (needsNotifications) {
                                    PermissionRow(
                                        icon = Icons.Default.Notifications,
                                        title = "Notifications",
                                        description = "Playback controls on the lock screen and notification shade.",
                                        granted = notificationsGranted,
                                        onAllow = { permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
                                    )
                                }
                                if (needsBluetooth) {
                                    PermissionRow(
                                        icon = Icons.Default.Bluetooth,
                                        title = "Bluetooth",
                                        description = "Know which headphones or speaker is connected.",
                                        granted = bluetoothGranted,
                                        onAllow = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }
                                    )
                                }
                            }
                        }

                        else -> {
                            Header(
                                title = "Your music folder",
                                subtitle = "Choose where your songs live so Transient can organise and manage them."
                            )
                            SetupCard {
                                PermissionRow(
                                    icon = Icons.Default.Folder,
                                    title = "Music folder",
                                    description = if (folderGranted) "Folder selected." else "Optional. Needed to read lyrics files and delete songs.",
                                    granted = folderGranted,
                                    allowLabel = "Choose",
                                    onAllow = { folderLauncher.launch(initialFolderUri) }
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Show all folders",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Off: only songs from the folder above. On: every music folder on the device.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Switch(
                                        checked = showAllFolders,
                                        onCheckedChange = {
                                            showAllFolders = it
                                            settings.showAllFoldersOnStart = it
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom actions stay put while the page above scrolls.
            val isAccessPage = page == PAGE_ACCESS
            val isLastPage = page == PAGE_LIBRARY
            Button(
                onClick = {
                    when {
                        isAccessPage && !allPermissionsGranted -> permissionLauncher.launch(missingPermissions.toTypedArray())
                        isLastPage -> onStartClick()
                        else -> page++
                    }
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = when {
                        isAccessPage && !allPermissionsGranted -> "Allow all"
                        isLastPage -> "Start listening"
                        else -> "Next"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page > PAGE_LOOK) {
                    TextButton(onClick = { page-- }) {
                        Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (isAccessPage && !allPermissionsGranted) {
                    TextButton(onClick = { page++ }) {
                        Text("Skip for now", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (isLastPage && !folderGranted) {
                    TextButton(onClick = onStartClick) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Progress dots: the current page is a longer pill. */
@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until count) {
            val width by animateDpAsState(if (i == current) 26.dp else 8.dp, label = "dotWidth")
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (i <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                    )
            )
        }
    }
}

/** Logo, name and a short line of text at the top of each page. */
@Composable
private fun Header(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(size = 46.dp)
            Spacer(modifier = Modifier.width(10.dp))
            BrandWordmark(height = 24.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SetupCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
        border = glassBorder(),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun ThemeChoice(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One permission (or folder) line: round icon, name and explanation, and an Allow button or an "Allowed" tick. */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onAllow: () -> Unit,
    allowLabel: String = "Allow"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        if (granted) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
        } else {
            Button(
                onClick = onAllow,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(text = allowLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}
