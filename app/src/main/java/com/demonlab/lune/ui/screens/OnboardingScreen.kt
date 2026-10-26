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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.demonlab.lune.R
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.AuroraBackdrop
import com.demonlab.lune.ui.components.BrandLogo
import com.demonlab.lune.ui.components.BrandWordmark
import com.demonlab.lune.ui.components.glassBorder

private enum class SetupStep { WELCOME, MUSIC, FOLDER, NOTIFICATIONS, BLUETOOTH, VISIBILITY, DONE }

/**
 * First-run setup: a short sequence of full-screen cards (one decision per screen) on the Aurora gradient.
 * Every permission is optional here except that the app asks again later if music access was skipped.
 */
@Composable
fun OnboardingScreen(
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
    var folderGranted by remember { mutableStateOf(settings.musicFolderUri != null) }
    var notificationsGranted by remember { mutableStateOf(!needsNotifications || has(Manifest.permission.POST_NOTIFICATIONS)) }
    var bluetoothGranted by remember { mutableStateOf(!needsBluetooth || has(Manifest.permission.BLUETOOTH_CONNECT)) }
    var showAllFolders by remember { mutableStateOf(settings.showAllFoldersOnStart) }

    // Steps that do not exist on this Android version are left out.
    val steps = remember {
        SetupStep.values().filter {
            when (it) {
                SetupStep.NOTIFICATIONS -> needsNotifications
                SetupStep.BLUETOOTH -> needsBluetooth
                else -> true
            }
        }
    }
    var index by remember { mutableIntStateOf(0) }
    val goNext: () -> Unit = { if (index < steps.lastIndex) index++ }

    val musicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { musicGranted = it }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bluetoothGranted = it }

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

    BackHandler(enabled = index > 0) { index-- }

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
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            StepDots(count = steps.size, current = index)

            AnimatedContent(
                targetState = index,
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
                label = "setupStep"
            ) { i ->
                when (steps[i]) {
                    SetupStep.WELCOME -> SetupPage(
                        hero = { BrandLogo(size = 96.dp, pulsing = true) },
                        title = stringResource(R.string.onboarding_welcome_back),
                        description = stringResource(R.string.onboarding_description),
                        extra = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 20.dp)
                            ) {
                                FeatureRow(Icons.Default.GraphicEq, stringResource(R.string.onboarding_feature_hifi))
                                FeatureRow(Icons.Default.Usb, "Bit-perfect USB DAC output")
                                FeatureRow(Icons.Default.Lyrics, stringResource(R.string.onboarding_feature_lyrics))
                            }
                        },
                        primaryLabel = stringResource(R.string.onboarding_start_button),
                        onPrimary = { goNext() }
                    )

                    SetupStep.MUSIC -> SetupPage(
                        icon = Icons.Default.MusicNote,
                        title = stringResource(R.string.onboarding_perm_music_title),
                        description = stringResource(R.string.onboarding_perm_music_desc),
                        granted = musicGranted,
                        primaryLabel = stringResource(R.string.onboarding_grant_button),
                        onPrimary = { musicLauncher.launch(musicPermission) },
                        onNext = { goNext() }
                    )

                    SetupStep.FOLDER -> SetupPage(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.onboarding_perm_manage_files_title),
                        description = stringResource(R.string.onboarding_perm_manage_files_desc),
                        granted = folderGranted,
                        primaryLabel = "Choose folder",
                        onPrimary = { folderLauncher.launch(initialFolderUri) },
                        onNext = { goNext() }
                    )

                    SetupStep.NOTIFICATIONS -> SetupPage(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.onboarding_perm_notifications_title),
                        description = stringResource(R.string.onboarding_perm_notifications_desc),
                        granted = notificationsGranted,
                        primaryLabel = stringResource(R.string.onboarding_grant_button),
                        onPrimary = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onNext = { goNext() }
                    )

                    SetupStep.BLUETOOTH -> SetupPage(
                        icon = Icons.Default.Bluetooth,
                        title = stringResource(R.string.onboarding_perm_bluetooth_title),
                        description = stringResource(R.string.onboarding_perm_bluetooth_desc),
                        granted = bluetoothGranted,
                        primaryLabel = stringResource(R.string.onboarding_grant_button),
                        onPrimary = { bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                        onNext = { goNext() }
                    )

                    SetupStep.VISIBILITY -> SetupPage(
                        icon = Icons.Default.Visibility,
                        title = stringResource(R.string.onboarding_folder_visibility_title),
                        description = stringResource(R.string.onboarding_folder_visibility_desc),
                        extra = {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                border = glassBorder(),
                                modifier = Modifier
                                    .padding(top = 20.dp)
                                    .fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.onboarding_folder_visibility_label),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = showAllFolders,
                                        onCheckedChange = {
                                            showAllFolders = it
                                            settings.showAllFoldersOnStart = it
                                        }
                                    )
                                }
                            }
                        },
                        primaryLabel = stringResource(R.string.onboarding_next_button),
                        onPrimary = { goNext() }
                    )

                    SetupStep.DONE -> SetupPage(
                        icon = Icons.Default.AutoAwesome,
                        title = stringResource(R.string.onboarding_reminder_title),
                        description = stringResource(R.string.onboarding_reminder_desc),
                        extra = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 20.dp)
                            ) {
                                FeatureRow(Icons.Default.Title, stringResource(R.string.onboarding_feature_title))
                                FeatureRow(Icons.Default.Lyrics, stringResource(R.string.onboarding_feature_lyrics))
                                FeatureRow(Icons.Default.GraphicEq, stringResource(R.string.onboarding_feature_mix))
                            }
                        },
                        primaryLabel = stringResource(R.string.onboarding_finish_button),
                        onPrimary = onStartClick
                    )
                }
            }
        }
    }
}

/** Progress dots: the current step is a longer pill. */
@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
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

/**
 * One setup screen. Pass [icon] for the gradient badge or a custom [hero]. With [onNext] set the page is a
 * permission page: the primary button asks for it, a granted state turns it into "Continue", and "Skip" moves on.
 */
@Composable
private fun SetupPage(
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    hero: (@Composable () -> Unit)? = null,
    granted: Boolean = false,
    onNext: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null
) {
    val isPermissionPage = onNext != null
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (hero != null) hero() else if (icon != null) HeroBadge(icon)
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (isPermissionPage && granted) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Allowed",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (extra != null) extra()
        }

        Button(
            onClick = { if (isPermissionPage && granted) onNext?.invoke() else onPrimary() },
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
                text = if (isPermissionPage && granted) stringResource(R.string.onboarding_next_button) else primaryLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (isPermissionPage && !granted) {
            TextButton(onClick = { onNext?.invoke() }, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_skip_button),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun HeroBadge(icon: ImageVector) {
    val pulse by rememberInfiniteTransition(label = "heroPulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "heroScale"
    )
    Box(
        modifier = Modifier
            .size(132.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(60.dp)
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        border = glassBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
