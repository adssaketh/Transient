package com.demonlab.lune.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.demonlab.lune.R
import com.demonlab.lune.tools.PlaybackManager
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.ui.components.AppBlurBackdrop
import com.demonlab.lune.ui.components.BouncySwitch
import com.demonlab.lune.ui.theme.LuneTheme
import com.demonlab.lune.ui.utils.bounceClick

/** Switches for the player / library behaviours (exit prompt, indexer, Hi-Res logo, album page extras...). */
class PlayerLibraryOptionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsManager = SettingsManager.getInstance(this)
        enableEdgeToEdge()
        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()
            val targetDarkTheme = when (settingsManager.themeMode) {
                1 -> false
                2 -> true
                else -> systemInDarkTheme
            }
            var useCustomColors by remember { mutableStateOf(settingsManager.useCustomColors) }
            var customColorPalette by remember { mutableIntStateOf(settingsManager.customColorPalette) }
            var useAmoledPitchBlack by remember { mutableStateOf(settingsManager.useAmoledPitchBlack) }

            LuneTheme(
                darkTheme = targetDarkTheme,
                useCustomColors = useCustomColors,
                customColorPalette = customColorPalette,
                useAmoledPitchBlack = useAmoledPitchBlack
            ) {
                PlayerLibraryOptionsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerLibraryOptionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager.getInstance(context) }
    val currentSong = PlaybackManager.getInstance(context).currentSong

    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (settingsManager.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemDark
    }
    val hasBlurBackground = settingsManager.isBlurEnabled &&
        ((isDarkTheme && settingsManager.isBlurDarkMode) || (!isDarkTheme && settingsManager.isBlurLightMode))
    val onBlur = hasBlurBackground && currentSong != null

    var askToExit by remember { mutableStateOf(settingsManager.askToExit) }
    var alphabetIndexer by remember { mutableStateOf(settingsManager.isAlphabetIndexerEnabled) }
    var swipeTabs by remember { mutableStateOf(settingsManager.isSwipeBetweenTabsEnabled) }
    var hiResLogo by remember { mutableStateOf(settingsManager.showHiResLogo) }
    var trackNumbers by remember { mutableStateOf(settingsManager.showTrackNumbersInAlbum) }
    var albumYear by remember { mutableStateOf(settingsManager.showAlbumYear) }
    var moreAlbums by remember { mutableStateOf(settingsManager.showMoreAlbumsByArtist) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AppBlurBackdrop(
        hasBlurBackground = hasBlurBackground,
        isDarkTheme = isDarkTheme,
        currentSong = currentSong
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = if (onBlur) Color.Transparent else MaterialTheme.colorScheme.surface,
            topBar = {
                LargeTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.options_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineLarge,
                            color = if (onBlur) Color.White else MaterialTheme.colorScheme.primary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.bounceClick()) {
                            Surface(
                                shape = CircleShape,
                                color = if (onBlur) Color.White.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.cd_back),
                                        tint = if (onBlur) Color.White else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = if (onBlur) Color.Transparent else MaterialTheme.colorScheme.surface,
                        titleContentColor = if (onBlur) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SettingsSection(title = stringResource(R.string.options_section_behaviour)) {
                    OptionSwitch(
                        R.string.opt_ask_exit, R.string.opt_ask_exit_desc,
                        Icons.AutoMirrored.Filled.ExitToApp, SectionPosition.FIRST, askToExit
                    ) { askToExit = it; settingsManager.askToExit = it }
                    OptionSwitch(
                        R.string.opt_alphabet_indexer, R.string.opt_alphabet_indexer_desc,
                        Icons.Default.SortByAlpha, SectionPosition.MIDDLE, alphabetIndexer
                    ) { alphabetIndexer = it; settingsManager.isAlphabetIndexerEnabled = it }
                    OptionSwitch(
                        R.string.opt_swipe_tabs, R.string.opt_swipe_tabs_desc,
                        Icons.Default.SwapHoriz, SectionPosition.LAST, swipeTabs
                    ) { swipeTabs = it; settingsManager.isSwipeBetweenTabsEnabled = it }
                }

                Spacer(modifier = Modifier.height(24.dp))

                SettingsSection(title = stringResource(R.string.options_section_display)) {
                    OptionSwitch(
                        R.string.opt_hires_logo, R.string.opt_hires_logo_desc,
                        Icons.Default.HighQuality, SectionPosition.FIRST, hiResLogo
                    ) { hiResLogo = it; settingsManager.showHiResLogo = it }
                    OptionSwitch(
                        R.string.opt_track_numbers, R.string.opt_track_numbers_desc,
                        Icons.Default.FormatListNumbered, SectionPosition.MIDDLE, trackNumbers
                    ) { trackNumbers = it; settingsManager.showTrackNumbersInAlbum = it }
                    OptionSwitch(
                        R.string.opt_album_year, R.string.opt_album_year_desc,
                        Icons.Default.CalendarMonth, SectionPosition.MIDDLE, albumYear
                    ) { albumYear = it; settingsManager.showAlbumYear = it }
                    OptionSwitch(
                        R.string.opt_more_albums, R.string.opt_more_albums_desc,
                        Icons.Default.Album, SectionPosition.LAST, moreAlbums
                    ) { moreAlbums = it; settingsManager.showMoreAlbumsByArtist = it }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun OptionSwitch(
    @StringRes title: Int,
    @StringRes description: Int,
    icon: ImageVector,
    position: SectionPosition,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsPreferenceItem(
        headlineText = stringResource(title),
        supportingText = stringResource(description),
        icon = icon,
        position = position,
        onClick = { onCheckedChange(!checked) },
        trailingContent = {
            BouncySwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            )
        }
    )
}
