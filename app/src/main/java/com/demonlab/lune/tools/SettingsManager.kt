package com.demonlab.lune.tools

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("lune_settings", Context.MODE_PRIVATE)

    init {
        // Design migrations run once per version (all of it can still be changed in settings).
        val designVersion = prefs.getInt("design_version", 0)
        if (designVersion < 4) {
            val editor = prefs.edit()
            if (designVersion < 2) {
                // Aurora redesign: Aurora Glass palette and glass look.
                editor.putBoolean("use_custom_colors", true)
                    .putInt("custom_color_palette", 6)
                    .putBoolean("glass_ui_enabled", true)
            }
            // Round covers turn like an audio CD while playing.
            editor.putBoolean("cover_spin", true)
            editor.putInt("design_version", 4)
                .apply()
        }
    }

    private val _customTitleFlow = MutableStateFlow(prefs.getString("custom_title", "") ?: "")
    val customTitleFlow: StateFlow<String> = _customTitleFlow.asStateFlow()


    var isFirstRun: Boolean
        get() = prefs.getBoolean("is_first_run", true)
        set(value) = prefs.edit().putBoolean("is_first_run", value).apply()

    var optionsOrder: String
        get() = prefs.getString("options_order", "like,shuffle,repeat,crossfade,automix,timer,eq") ?: "like,shuffle,repeat,crossfade,automix,timer,eq"
        set(value) = prefs.edit().putString("options_order", value).apply()

    var hiddenFolders: Set<String>
        get() = prefs.getStringSet("hidden_folders", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_folders", value).apply()

    // Deprecated: used for backward compatibility if needed, but we use hiddenFolders now
    var showWhatsappAudio: Boolean
        get() = !hiddenFolders.contains("WhatsApp Audio")
        set(value) {
            val folders = hiddenFolders.toMutableSet()
            if (value) folders.remove("WhatsApp Audio") else folders.add("WhatsApp Audio")
            hiddenFolders = folders
        }

    var enableHiFi: Boolean
        get() = prefs.getBoolean("enable_hifi", true)
        set(value) = prefs.edit().putBoolean("enable_hifi", value).apply()

    var is32BitFloatEnabled: Boolean
        get() = prefs.getBoolean("is_32bit_float_enabled", true)
        set(value) = prefs.edit().putBoolean("is_32bit_float_enabled", value).apply()

    var isExclusiveModeEnabled: Boolean
        get() = prefs.getBoolean("is_exclusive_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("is_exclusive_mode_enabled", value).apply()

    /** Send audio straight to a USB Audio Class 2.0 DAC, bypassing Android's mixer. Opt-in: it takes the DAC away from the OS. */
    var isUsbBitPerfectEnabled: Boolean
        get() = prefs.getBoolean("is_usb_bit_perfect_enabled", false)
        set(value) = prefs.edit().putBoolean("is_usb_bit_perfect_enabled", value).apply()

    // ── Player & library options (all on by default) ──
    var askToExit: Boolean
        get() = prefs.getBoolean("ask_to_exit", true)
        set(value) = prefs.edit().putBoolean("ask_to_exit", value).apply()

    var isAlphabetIndexerEnabled: Boolean
        get() = prefs.getBoolean("alphabet_indexer", true)
        set(value) = prefs.edit().putBoolean("alphabet_indexer", value).apply()

    var isSwipeBetweenTabsEnabled: Boolean
        get() = prefs.getBoolean("swipe_between_tabs", true)
        set(value) = prefs.edit().putBoolean("swipe_between_tabs", value).apply()

    var showHiResLogo: Boolean
        get() = prefs.getBoolean("show_hires_logo", true)
        set(value) = prefs.edit().putBoolean("show_hires_logo", value).apply()

    var showTrackNumbersInAlbum: Boolean
        get() = prefs.getBoolean("show_track_numbers_album", true)
        set(value) = prefs.edit().putBoolean("show_track_numbers_album", value).apply()

    var showAlbumYear: Boolean
        get() = prefs.getBoolean("show_album_year", true)
        set(value) = prefs.edit().putBoolean("show_album_year", value).apply()

    var showMoreAlbumsByArtist: Boolean
        get() = prefs.getBoolean("show_more_albums_by_artist", true)
        set(value) = prefs.edit().putBoolean("show_more_albums_by_artist", value).apply()

    /** Use each song's own embedded cover before Android's (album/folder-level) thumbnail. */
    var preferEmbeddedArt: Boolean
        get() = prefs.getBoolean("prefer_embedded_art", true)
        set(value) = prefs.edit().putBoolean("prefer_embedded_art", value).apply()

    /** Phone volume buttons control the volume of USB output (digital gain; max volume stays bit-perfect). */
    var isUsbSoftwareVolumeEnabled: Boolean
        get() = prefs.getBoolean("usb_software_volume", true)
        set(value) = prefs.edit().putBoolean("usb_software_volume", value).apply()

    /** Columns of the All songs grid (2-4); pinching to a single column switches back to the list. */
    var songGridColumns: Int
        get() = prefs.getInt("song_grid_columns", 2).coerceIn(2, 4)
        set(value) = prefs.edit().putInt("song_grid_columns", value.coerceIn(2, 4)).apply()

    /** Columns in the Albums / Artists / Genres grids; changed by pinching. */
    var albumGridColumns: Int
        get() = prefs.getInt("album_grid_columns", 2).coerceIn(1, 5)
        set(value) = prefs.edit().putInt("album_grid_columns", value.coerceIn(1, 5)).apply()

    var stopOnTaskRemoved: Boolean
        get() = prefs.getBoolean("stop_on_task_removed", false)
        set(value) = prefs.edit().putBoolean("stop_on_task_removed", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", false)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var showSectionHeaders: Boolean
        get() = prefs.getBoolean("show_section_headers", true)
        set(value) = prefs.edit().putBoolean("show_section_headers", value).apply()

    private val _themeMode = mutableStateOf(prefs.getInt("theme_mode", 0))
    var themeMode: Int
        get() = _themeMode.value
        set(value) {
            _themeMode.value = value
            prefs.edit().putInt("theme_mode", value).apply()
        }

    var forceDarkMode: Boolean
        get() = prefs.getBoolean("force_dark_mode", false)
        set(value) = prefs.edit().putBoolean("force_dark_mode", value).apply()

    var sortOption: String
        get() = prefs.getString("sort_option", "ALPHABETICAL") ?: "ALPHABETICAL"
        set(value) = prefs.edit().putString("sort_option", value).apply()

    fun getSortOption(key: String): String {
        return prefs.getString("sort_option_$key", sortOption) ?: sortOption
    }

    fun setSortOption(key: String, option: String) {
        prefs.edit().putString("sort_option_$key", option).apply()
    }

    var tracksViewStyle: Int
        get() = prefs.getInt("tracks_view_style", 0) // 0 = List, 1 = Grid
        set(value) = prefs.edit().putInt("tracks_view_style", value).apply()

    var albumViewStyle: Int
        get() = prefs.getInt("album_view_style", 0) // 0 = Grid, 1 = Carousel
        set(value) = prefs.edit().putInt("album_view_style", value).apply()

    var albumBrowseMode: Boolean
        get() = prefs.getBoolean("album_browse_mode", false) // false = Artists, true = Albums
        set(value) = prefs.edit().putBoolean("album_browse_mode", value).apply()

    var isSectionCustomizationEnabled: Boolean
        get() = prefs.getBoolean("section_customization_enabled", false)
        set(value) = prefs.edit().putBoolean("section_customization_enabled", value).apply()

    var isScrollToTopEnabled: Boolean
        get() = prefs.getBoolean("scroll_to_top_enabled", true)
        set(value) = prefs.edit().putBoolean("scroll_to_top_enabled", value).apply()

    var widgetUseSolidBackground: Boolean
        get() = prefs.getBoolean("widget_use_solid_background", false)
        set(value) = prefs.edit().putBoolean("widget_use_solid_background", value).apply()

    var widgetLightBackgroundColor: Int
        get() = prefs.getInt("widget_light_background_color", 0xFFFFFFFF.toInt())
        set(value) = prefs.edit().putInt("widget_light_background_color", value).apply()

    var widgetDarkBackgroundColor: Int
        get() = prefs.getInt("widget_dark_background_color", 0xFF1E1E24.toInt())
        set(value) = prefs.edit().putInt("widget_dark_background_color", value).apply()

    var widgetBackgroundDarkness: Float
        get() = prefs.getFloat("widget_background_darkness", 0.50f)
        set(value) = prefs.edit().putFloat("widget_background_darkness", value).apply()

    var widgetBackgroundBlur: Int
        get() = prefs.getInt("widget_background_blur", 50)
        set(value) = prefs.edit().putInt("widget_background_blur", value).apply()

    var widgetCircularCover: Boolean
        get() = prefs.getBoolean("widget_circular_cover", false)
        set(value) = prefs.edit().putBoolean("widget_circular_cover", value).apply()

    var widgetVinylCover: Boolean
        get() = prefs.getBoolean("widget_vinyl_cover", false)
        set(value) = prefs.edit().putBoolean("widget_vinyl_cover", value).apply()

    var hiddenSectionTabs: Set<String>
        get() = prefs.getStringSet("hidden_section_tabs", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_section_tabs", value).apply()

    var defaultSectionTab: String
        get() = prefs.getString("default_section_tab", "") ?: ""
        set(value) = prefs.edit().putString("default_section_tab", value).apply()

    var isSortAscending: Boolean
        get() = prefs.getBoolean("is_sort_ascending", true)
        set(value) = prefs.edit().putBoolean("is_sort_ascending", value).apply()

    fun getIsSortAscending(key: String): Boolean {
        return prefs.getBoolean("is_sort_ascending_$key", isSortAscending)
    }

    fun setIsSortAscending(key: String, ascending: Boolean) {
        prefs.edit().putBoolean("is_sort_ascending_$key", ascending).apply()
    }

    var isCaseSensitiveSort: Boolean
        get() = prefs.getBoolean("is_case_sensitive_sort", false)
        set(value) = prefs.edit().putBoolean("is_case_sensitive_sort", value).apply()

    fun getIsCaseSensitiveSort(key: String): Boolean {
        return prefs.getBoolean("is_case_sensitive_sort_$key", isCaseSensitiveSort)
    }

    fun setIsCaseSensitiveSort(key: String, caseSensitive: Boolean) {
        prefs.edit().putBoolean("is_case_sensitive_sort_$key", caseSensitive).apply()
    }

    var isShuffle: Boolean
        get() = prefs.getBoolean("is_shuffle", false)
        set(value) = prefs.edit().putBoolean("is_shuffle", value).apply()

    var isCrossfade: Boolean
        get() = prefs.getBoolean("is_crossfade", false)
        set(value) = prefs.edit().putBoolean("is_crossfade", value).apply()

    var isCrossfadeCustomDuration: Boolean
        get() = prefs.getBoolean("is_crossfade_custom_duration", false)
        set(value) = prefs.edit().putBoolean("is_crossfade_custom_duration", value).apply()

    var crossfadeDurationSeconds: Int
        get() = prefs.getInt("crossfade_duration_seconds", 12)
        set(value) = prefs.edit().putInt("crossfade_duration_seconds", value).apply()

    var isAutomix: Boolean
        get() = prefs.getBoolean("is_automix", false)
        set(value) = prefs.edit().putBoolean("is_automix", value).apply()

    var repeatMode: Int
        get() = prefs.getInt("repeat_mode", 0) // 0: Off, 1: One, 2: All
        set(value) = prefs.edit().putInt("repeat_mode", value).apply()

    var seamlessLooping: Boolean
        get() = prefs.getBoolean("seamless_looping", true)
        set(value) = prefs.edit().putBoolean("seamless_looping", value).apply()

    var isEqEnabled: Boolean
        get() = prefs.getBoolean("is_eq_enabled", false)
        set(value) = prefs.edit().putBoolean("is_eq_enabled", value).apply()

    var eqBandLevels: String
        get() = prefs.getString("eq_band_levels", "") ?: ""
        set(value) = prefs.edit().putString("eq_band_levels", value).apply()

    var activeEqPresetName: String
        get() = prefs.getString("active_eq_preset_name", "") ?: ""
        set(value) = prefs.edit().putString("active_eq_preset_name", value).apply()

    var lastEqPresetName: String
        get() = prefs.getString("last_eq_preset_name", "") ?: ""
        set(value) = prefs.edit().putString("last_eq_preset_name", value).apply()

    var lastEqBandLevels: String
        get() = prefs.getString("last_eq_band_levels", "") ?: ""
        set(value) = prefs.edit().putString("last_eq_band_levels", value).apply()

    var customEqPresetsJson: String
        get() = prefs.getString("custom_eq_presets", "[]") ?: "[]"
        set(value) = prefs.edit().putString("custom_eq_presets", value).apply()

    var isBassBoostEnabled: Boolean
        get() = prefs.getBoolean("is_bass_enabled", false)
        set(value) = prefs.edit().putBoolean("is_bass_enabled", value).apply()

    var bassBoostLevel: Int
        get() = prefs.getInt("bass_boost_level", 900)
        set(value) = prefs.edit().putInt("bass_boost_level", value).apply()

    var isSpatialAudioEnabled: Boolean
        get() = prefs.getBoolean("is_spatial_audio_enabled", false)
        set(value) {
            prefs.edit().putBoolean("is_spatial_audio_enabled", value).apply()
        }

    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(value) = prefs.edit().putString("language", value).apply()

    var customTitle: String
        get() = prefs.getString("custom_title", "") ?: ""
        set(value) {
            prefs.edit().putString("custom_title", value).apply()
            _customTitleFlow.value = value
        }

    var lastPlayedSongId: Long
        get() = prefs.getLong("last_played_song_id", -1L)
        set(value) = prefs.edit().putLong("last_played_song_id", value).apply()

    var lastCategory: String
        get() = prefs.getString("last_category", "") ?: ""
        set(value) = prefs.edit().putString("last_category", value).apply()



    var activeCategory: String
        get() = prefs.getString("active_category", "") ?: ""
        set(value) = prefs.edit().putString("active_category", value).apply()

    var isCinematicPlayerEnabled: Boolean
        get() = prefs.getBoolean("is_cinematic_player_enabled", false)
        set(value) = prefs.edit().putBoolean("is_cinematic_player_enabled", value).apply()
        
    var isHapticVibrationEnabled: Boolean
        get() = prefs.getBoolean("is_haptic_vibration_enabled", true)
        set(value) = prefs.edit().putBoolean("is_haptic_vibration_enabled", value).apply()

    var isSongInfoEnabled: Boolean
        get() = prefs.getBoolean("is_song_info_enabled", false)
        set(value) = prefs.edit().putBoolean("is_song_info_enabled", value).apply()

    private val _isBitrateOnList = mutableStateOf(prefs.getBoolean("is_bitrate_on_list", true))
    var isBitrateOnList: Boolean
        get() = _isBitrateOnList.value
        set(value) {
            _isBitrateOnList.value = value
            prefs.edit().putBoolean("is_bitrate_on_list", value).apply()
        }

    private val _isBitrateOnPlayer = mutableStateOf(prefs.getBoolean("is_bitrate_on_player", true))
    var isBitrateOnPlayer: Boolean
        get() = _isBitrateOnPlayer.value
        set(value) {
            _isBitrateOnPlayer.value = value
            prefs.edit().putBoolean("is_bitrate_on_player", value).apply()
        }

    private val _isOptionsBarVisible = mutableStateOf(prefs.getBoolean("is_options_bar_visible", false))
    var isOptionsBarVisible: Boolean
        get() = _isOptionsBarVisible.value
        set(value) {
            _isOptionsBarVisible.value = value
            prefs.edit().putBoolean("is_options_bar_visible", value).apply()
        }

    private val _isMiniPlayerMinimized = mutableStateOf(prefs.getBoolean("is_mini_player_minimized", false))
    var isMiniPlayerMinimized: Boolean
        get() = _isMiniPlayerMinimized.value
        set(value) {
            _isMiniPlayerMinimized.value = value
            prefs.edit().putBoolean("is_mini_player_minimized", value).apply()
        }

    private val _isBlurEnabled = mutableStateOf(prefs.getBoolean("is_blur_enabled", true))
    var isBlurEnabled: Boolean
        get() = _isBlurEnabled.value
        set(value) {
            _isBlurEnabled.value = value
            prefs.edit().putBoolean("is_blur_enabled", value).apply()
        }

    private val _isBlurDarkMode = mutableStateOf(prefs.getBoolean("is_blur_dark_mode", true))
    var isBlurDarkMode: Boolean
        get() = _isBlurDarkMode.value
        set(value) {
            _isBlurDarkMode.value = value
            prefs.edit().putBoolean("is_blur_dark_mode", value).apply()
        }

    private val _isBlurLightMode = mutableStateOf(prefs.getBoolean("is_blur_light_mode", false))
    var isBlurLightMode: Boolean
        get() = _isBlurLightMode.value
        set(value) {
            _isBlurLightMode.value = value
            prefs.edit().putBoolean("is_blur_light_mode", value).apply()
        }

    private val _isBlurCinematicMode = mutableStateOf(prefs.getBoolean("is_blur_cinematic_mode", false))
    var isBlurCinematicMode: Boolean
        get() = _isBlurCinematicMode.value
        set(value) {
            _isBlurCinematicMode.value = value
            prefs.edit().putBoolean("is_blur_cinematic_mode", value).apply()
        }

    /** Smooth blur: the full-screen background uses a tiny scaled-up cover instead of a heavy live blur. ON by default. */
    private val _isBlurSmoothMode = mutableStateOf(prefs.getBoolean("is_blur_smooth_mode", true))
    var isBlurSmoothMode: Boolean
        get() = _isBlurSmoothMode.value
        set(value) {
            _isBlurSmoothMode.value = value
            prefs.edit().putBoolean("is_blur_smooth_mode", value).apply()
        }

    private val _isBlurControlsEnabled = mutableStateOf(prefs.getBoolean("is_blur_controls_enabled", false))
    var isBlurControlsEnabled: Boolean
        get() = _isBlurControlsEnabled.value
        set(value) {
            _isBlurControlsEnabled.value = value
            prefs.edit().putBoolean("is_blur_controls_enabled", value).apply()
        }

    var lyricsTextAlignment: Int
        get() = prefs.getInt("lyrics_text_alignment", 0)
        set(value) = prefs.edit().putInt("lyrics_text_alignment", value).apply()

    var lyricsSpeedIndex: Int
        get() = prefs.getInt("lyrics_speed_index", 0)
        set(value) = prefs.edit().putInt("lyrics_speed_index", value).apply()

    private val _isGesturesEnabled = mutableStateOf(prefs.getBoolean("is_gestures_enabled", false))
    var isGesturesEnabled: Boolean
        get() = _isGesturesEnabled.value
        set(value) {
            _isGesturesEnabled.value = value
            prefs.edit().putBoolean("is_gestures_enabled", value).apply()
        }

    var swipeUpAction: Int
        get() = prefs.getInt("swipe_up_action", 0)
        set(value) = prefs.edit().putInt("swipe_up_action", value).apply()

    var dailyListeningTime: Long
        get() = prefs.getLong("daily_listening_time", 0L)
        set(value) = prefs.edit().putLong("daily_listening_time", value).apply()

    var lastStatsResetTimestamp: Long
        get() = prefs.getLong("last_stats_reset_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_stats_reset_timestamp", value).apply()

    var musicFolderUri: String?
        get() = prefs.getString("music_folder_uri", null)
        set(value) = prefs.edit().putString("music_folder_uri", value).apply()
        
    var isInitialFolderScanPending: Boolean
        get() = prefs.getBoolean("is_initial_folder_scan_pending", false)
        set(value) = prefs.edit().putBoolean("is_initial_folder_scan_pending", value).apply()

    var showAllFoldersOnStart: Boolean
        get() = prefs.getBoolean("show_all_folders_on_start", false)
        set(value) = prefs.edit().putBoolean("show_all_folders_on_start", value).apply()

    var folderHierarchyMode: Boolean
        get() = prefs.getBoolean("folder_hierarchy_mode", false)
        set(value) = prefs.edit().putBoolean("folder_hierarchy_mode", value).apply()

    var useCustomColors: Boolean
        get() = prefs.getBoolean("use_custom_colors", false)
        set(value) = prefs.edit().putBoolean("use_custom_colors", value).apply()

    var customColorPalette: Int
        get() = prefs.getInt("custom_color_palette", 0)
        set(value) = prefs.edit().putInt("custom_color_palette", value).apply()

    // Album-art colour scheme (default OFF). The seed colour is the last colour extracted from the playing song's art.
    private val _albumArtColorsEnabled = mutableStateOf(prefs.getBoolean("album_art_colors_enabled", false))
    var albumArtColorsEnabled: Boolean
        get() = _albumArtColorsEnabled.value
        set(value) {
            _albumArtColorsEnabled.value = value
            prefs.edit().putBoolean("album_art_colors_enabled", value).apply()
        }

    private val _albumArtSeedColor = mutableStateOf(prefs.getInt("album_art_seed_color", 0))
    var albumArtSeedColor: Int
        get() = _albumArtSeedColor.value
        set(value) {
            _albumArtSeedColor.value = value
            prefs.edit().putInt("album_art_seed_color", value).apply()
        }

    // Glass UI (default ON): frosted-glass look for the mini player, section pill and cards.
    private val _glassUiEnabled = mutableStateOf(prefs.getBoolean("glass_ui_enabled", true))
    var glassUiEnabled: Boolean
        get() = _glassUiEnabled.value
        set(value) {
            _glassUiEnabled.value = value
            prefs.edit().putBoolean("glass_ui_enabled", value).apply()
        }

    // Now playing in the header (default ON): while music plays, the app name gives way to "Title · Artist".
    private val _headerNowPlayingEnabled = mutableStateOf(prefs.getBoolean("header_now_playing_enabled", true))
    var headerNowPlayingEnabled: Boolean
        get() = _headerNowPlayingEnabled.value
        set(value) {
            _headerNowPlayingEnabled.value = value
            prefs.edit().putBoolean("header_now_playing_enabled", value).apply()
        }

    // Jewel case cover (default ON): the full player shows the album art inside a CD jewel case.
    private val _jewelCaseCover = mutableStateOf(prefs.getBoolean("jewel_case_cover", true))
    var jewelCaseCover: Boolean
        get() = _jewelCaseCover.value
        set(value) {
            _jewelCaseCover.value = value
            prefs.edit().putBoolean("jewel_case_cover", value).apply()
        }

    // 3D sway of the jewel case with the phone's movement (default OFF).
    private val _jewelCaseSway = mutableStateOf(prefs.getBoolean("jewel_case_sway", false))
    var jewelCaseSway: Boolean
        get() = _jewelCaseSway.value
        set(value) {
            _jewelCaseSway.value = value
            prefs.edit().putBoolean("jewel_case_sway", value).apply()
        }

    // Reflection under the jewel case (all three apply to every jewel case in the app).
    private val _jewelReflectionStrength = mutableStateOf(prefs.getInt("jewel_refl_strength", 40).coerceIn(0, 100))
    /** How visible the reflection is, 0-100 %. */
    var jewelReflectionStrength: Int
        get() = _jewelReflectionStrength.value
        set(value) {
            _jewelReflectionStrength.value = value.coerceIn(0, 100)
            prefs.edit().putInt("jewel_refl_strength", value.coerceIn(0, 100)).apply()
        }

    private val _jewelReflectionFade = mutableStateOf(prefs.getInt("jewel_refl_fade", 100).coerceIn(0, 100))
    /** How far down the reflection reaches before it has faded out, 0-100 % of the space under the case. */
    var jewelReflectionFade: Int
        get() = _jewelReflectionFade.value
        set(value) {
            _jewelReflectionFade.value = value.coerceIn(0, 100)
            prefs.edit().putInt("jewel_refl_fade", value.coerceIn(0, 100)).apply()
        }

    private val _jewelReflectionBlur = mutableStateOf(prefs.getInt("jewel_refl_blur", 0).coerceIn(0, 100))
    /** Blur of the reflection, 0-100 % (0 = sharp, 100 = 20 dp). Needs Android 12+. */
    var jewelReflectionBlur: Int
        get() = _jewelReflectionBlur.value
        set(value) {
            _jewelReflectionBlur.value = value.coerceIn(0, 100)
            prefs.edit().putInt("jewel_refl_blur", value.coerceIn(0, 100)).apply()
        }

    // Artist photos and short bios from the internet (Wikipedia / Deezer). Only the artist's name is sent. OFF by default:
    // until the user turns it on, the app makes no network requests.
    private val _onlineArtistInfo = mutableStateOf(prefs.getBoolean("online_artist_info", false))
    var onlineArtistInfo: Boolean
        get() = _onlineArtistInfo.value
        set(value) {
            _onlineArtistInfo.value = value
            prefs.edit().putBoolean("online_artist_info", value).apply()
        }

    // Crash / freeze / low-memory reports saved to the logs folder (see DiagnosticLogger). Stays on the phone; ON by default.
    private val _crashLogsEnabled = mutableStateOf(prefs.getBoolean("crash_logs_enabled", true))
    var crashLogsEnabled: Boolean
        get() = _crashLogsEnabled.value
        set(value) {
            _crashLogsEnabled.value = value
            prefs.edit().putBoolean("crash_logs_enabled", value).apply()
        }

    // Silent stream on the phone speaker that runs next to direct USB output (see SilentAudioKeepAlive). ON by default;
    // a switch in Settings > Diagnostics lets us test whether it helps or hurts. Takes effect from the next song.
    private val _usbKeepAliveEnabled = mutableStateOf(prefs.getBoolean("usb_keepalive_enabled", true))
    var usbKeepAliveEnabled: Boolean
        get() = _usbKeepAliveEnabled.value
        set(value) {
            _usbKeepAliveEnabled.value = value
            prefs.edit().putBoolean("usb_keepalive_enabled", value).apply()
        }

    var showBackupWarning: Boolean
        get() = prefs.getBoolean("show_backup_warning", true)
        set(value) = prefs.edit().putBoolean("show_backup_warning", value).apply()

    var isAutoSyncBackupEnabled: Boolean
        get() = prefs.getBoolean("auto_sync_backup_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_sync_backup_enabled", value).apply()

    var autoSyncBackupUri: String?
        get() = prefs.getString("auto_sync_backup_uri", null)
        set(value) = prefs.edit().putString("auto_sync_backup_uri", value).apply()

    var useAmoledPitchBlack: Boolean
        get() = prefs.getBoolean("use_amoled_pitch_black", false)
        set(value) = prefs.edit().putBoolean("use_amoled_pitch_black", value).apply()

    var showAiSection: Boolean
        get() = prefs.getBoolean("show_ai_section", true)
        set(value) = prefs.edit().putBoolean("show_ai_section", value).apply()

    private val _coverShape = mutableStateOf(prefs.getInt("cover_shape", 0))
    var coverShape: Int
        get() = _coverShape.value
        set(value) {
            _coverShape.value = value
            prefs.edit().putInt("cover_shape", value).apply()
        }

    private val _coverScale = mutableStateOf(prefs.getFloat("cover_scale", 1.0f))
    var coverScale: Float
        get() = _coverScale.value
        set(value) {
            _coverScale.value = value
            prefs.edit().putFloat("cover_scale", value).apply()
        }

    private val _coverSpin = mutableStateOf(prefs.getBoolean("cover_spin", true))
    var coverSpin: Boolean
        get() = _coverSpin.value
        set(value) {
            _coverSpin.value = value
            prefs.edit().putBoolean("cover_spin", value).apply()
        }

    private val _coverVinylEffect = mutableStateOf(prefs.getBoolean("cover_vinyl_effect", false))
    var coverVinylEffect: Boolean
        get() = _coverVinylEffect.value
        set(value) {
            _coverVinylEffect.value = value
            prefs.edit().putBoolean("cover_vinyl_effect", value).apply()
        }

    private val _controlsIconStyle = mutableStateOf(prefs.getInt("controls_icon_style", 0))
    var controlsIconStyle: Int
        get() = _controlsIconStyle.value
        set(value) {
            _controlsIconStyle.value = value
            prefs.edit().putInt("controls_icon_style", value).apply()
        }

    private val _isControlsFilled = mutableStateOf(prefs.getBoolean("is_controls_filled", false))
    var isControlsFilled: Boolean
        get() = _isControlsFilled.value
        set(value) {
            _isControlsFilled.value = value
            prefs.edit().putBoolean("is_controls_filled", value).apply()
        }

    private val _useCustomControlsColor = mutableStateOf(prefs.getBoolean("use_custom_controls_color", false))
    var useCustomControlsColor: Boolean
        get() = _useCustomControlsColor.value
        set(value) {
            _useCustomControlsColor.value = value
            prefs.edit().putBoolean("use_custom_controls_color", value).apply()
        }

    private val _controlsColorPalette = mutableStateOf(prefs.getInt("controls_color_palette", 0))
    var controlsColorPalette: Int
        get() = _controlsColorPalette.value
        set(value) {
            _controlsColorPalette.value = value
            prefs.edit().putInt("controls_color_palette", value).apply()
        }

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(value) = prefs.edit().putFloat("playback_speed", value).apply()

    var playbackPitch: Float
        get() = prefs.getFloat("playback_pitch", 1.0f)
        set(value) = prefs.edit().putFloat("playback_pitch", value).apply()

    var isTuning432: Boolean
        get() = prefs.getBoolean("tuning_432", false)
        set(value) = prefs.edit().putBoolean("tuning_432", value).apply()

    var reverbPreset: Int
        get() = prefs.getInt("reverb_preset", 0)
        set(value) = prefs.edit().putInt("reverb_preset", value).apply()

    var balance: Float
        get() = prefs.getFloat("balance", 0.5f)
        set(value) = prefs.edit().putFloat("balance", value).apply()

    var dynamicsPreset: Int
        get() = prefs.getInt("dynamics_preset", 0)
        set(value) = prefs.edit().putInt("dynamics_preset", value).apply()

    var isLoudnessEnabled: Boolean
        get() = prefs.getBoolean("is_loudness_enabled", false)
        set(value) = prefs.edit().putBoolean("is_loudness_enabled", value).apply()

    var loudnessGain: Int
        get() = prefs.getInt("loudness_gain", 0)
        set(value) = prefs.edit().putInt("loudness_gain", value).apply()

    fun getPlaylistShuffle(playlistId: Long): Boolean {
        return prefs.getBoolean("shuffle_playlist_$playlistId", false)
    }

    fun setPlaylistShuffle(playlistId: Long, value: Boolean) {
        prefs.edit().putBoolean("shuffle_playlist_$playlistId", value).apply()
    }

    companion object {
        @Volatile
        private var INSTANCE: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}