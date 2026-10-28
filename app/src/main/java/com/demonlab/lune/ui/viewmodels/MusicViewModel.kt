package com.demonlab.lune.ui.viewmodels

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.demonlab.lune.data.MusicDatabase
import com.demonlab.lune.data.PlaylistSong
import com.demonlab.lune.tools.MusicProvider
import com.demonlab.lune.tools.Song
import com.demonlab.lune.tools.SettingsManager
import com.demonlab.lune.tools.MetadataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val musicProvider = MusicProvider(application)
    private val metadataManager = MetadataManager(application)

    var allSongs by mutableStateOf<List<Song>>(emptyList())
        private set

    val visuallyDeletedIds = androidx.compose.runtime.mutableStateListOf<Long>()

    val filteredSongs: List<Song>
        get() = allSongs.filter { it.id !in visuallyDeletedIds }

    var isLoading by mutableStateOf(false)
        private set

    private var loadJob: kotlinx.coroutines.Job? = null

    fun loadSongs() {
        // Startup and every resume ask for a load, sometimes several times within a second (the logs showed three scans
        // back to back). One running scan is enough; the next resume starts another.
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) {
                musicProvider.getCachedSongs()
            }
            if (cached.isNotEmpty() && allSongs.isEmpty()) {
                allSongs = cached
            } else if (allSongs.isEmpty()) {
                isLoading = true
            }
            
            syncSongsInternal()
            isLoading = false
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            isLoading = true
            val refreshed = musicProvider.refreshLibrary()
            if (refreshed.isNotEmpty()) {
                allSongs = refreshed
            }
            isLoading = false
        }
    }

    private suspend fun syncSongsInternal() {
        val synced = musicProvider.syncSongs()
        
        if (synced.isNotEmpty()) {
            val settingsManager = SettingsManager.getInstance(getApplication())
            if (settingsManager.isInitialFolderScanPending) {
                if (!settingsManager.showAllFoldersOnStart) {
                    val uriStr = settingsManager.musicFolderUri
                    if (!uriStr.isNullOrBlank()) {
                        try {
                            val uri = android.net.Uri.parse(uriStr)
                            val pathSegment = uri.lastPathSegment?.substringAfterLast(":")
                            if (!pathSegment.isNullOrBlank()) {
                                val targetFolder = pathSegment.substringAfterLast("/")
                                val allowedFolderNames = synced.filter { song ->
                                    song.path.contains("/$pathSegment/", ignoreCase = true) ||
                                    song.path.contains("/$targetFolder/", ignoreCase = true)
                                }.map { it.folderName }.toSet()

                                val allFolderNames = synced.map { it.folderName }.toSet()
                                val foldersToHide = allFolderNames - allowedFolderNames
                                settingsManager.hiddenFolders = foldersToHide
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                settingsManager.isInitialFolderScanPending = false
            }
            
            if (synced != allSongs) {
                allSongs = synced
            }
        } else {
            // If sync returned empty but we already had songs, don't clear the list.
            // This prevents the "no songs" bug when permissions or MediaStore fail temporarily.
            if (allSongs.isEmpty()) {
                allSongs = emptyList()
            }
        }
    }

    fun updateMetadata(
        song: Song,
        title: String,
        artist: String,
        album: String,
        genre: String?,
        coverUri: android.net.Uri?,
        onSuccess: () -> Unit,
        extras: com.demonlab.lune.tools.ExtraTags? = null
    ) {
        viewModelScope.launch {
            val success = metadataManager.updateSongMetadata(
                songId = song.id,
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                coverUri = coverUri?.toString(),
                extras = extras
            )
            if (success) {
                allSongs = allSongs.map {
                    if (it.id == song.id) it.copy(
                        title = title,
                        artist = artist,
                        album = album,
                        genre = genre ?: it.genre,
                        coverUrl = coverUri?.toString() ?: it.coverUrl,
                        albumArtist = if (extras != null) extras.albumArtist.trim().ifEmpty { null } else it.albumArtist,
                        composer = if (extras != null) extras.composer.trim().ifEmpty { null } else it.composer,
                        trackNumber = extras?.trackNumber ?: it.trackNumber,
                        year = extras?.year ?: it.year
                    ) else it
                }
                onSuccess()
                syncSongsInternal()
            }
        }
    }

    fun restoreOriginalMetadata(song: Song, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val success = metadataManager.clearMetadataOverride(song.id)
            if (success) {
                syncSongsInternal()
                onSuccess()
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val success = metadataManager.updateFavoriteStatus(song.id, !song.isFavorite)
            if (success) {
                allSongs = allSongs.map {
                    if (it.id == song.id) it.copy(isFavorite = !song.isFavorite) else it
                }
            }
        }
    }

    fun syncFavoriteStatusInMemory(songId: Long, isFavorite: Boolean) {
        allSongs = allSongs.map {
            if (it.id == songId) it.copy(isFavorite = isFavorite) else it
        }
    }

    // PLAYLISTS
    var playlists by mutableStateOf<List<com.demonlab.lune.data.Playlist>>(emptyList())
        private set

    var playlistMappings by mutableStateOf<List<com.demonlab.lune.data.PlaylistSong>>(emptyList())
        private set

    var topSongStats by mutableStateOf<List<com.demonlab.lune.data.PlaybackStats>>(emptyList())
        private set
    var topPlaylistStats by mutableStateOf<List<com.demonlab.lune.data.PlaybackStats>>(emptyList())
        private set
    var topArtistStats by mutableStateOf<List<com.demonlab.lune.data.PlaybackStats>>(emptyList())
        private set
    var topGenreStats by mutableStateOf<List<com.demonlab.lune.data.PlaybackStats>>(emptyList())
        private set

    fun loadPlaylists() {
        viewModelScope.launch {
            val (newPlaylists, newMappings) = withContext(Dispatchers.IO) {
                val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
                Pair(db.playlistDao().getAllPlaylists(), db.playlistDao().getAllPlaylistMappings())
            }
            playlists = newPlaylists
            playlistMappings = newMappings
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().insertPlaylist(
                    com.demonlab.lune.data.Playlist(name = name)
                )
            }
            loadPlaylists()
            notifyPlaylistsChanged()
        }
    }

    private fun notifyPlaylistsChanged() {
        com.demonlab.lune.tools.PlaylistBackupManager(getApplication()).triggerAutoSync()
    }

    private suspend fun awaitLoadPlaylists() {
        val (newPlaylists, newMappings) = withContext(Dispatchers.IO) {
            val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
            Pair(db.playlistDao().getAllPlaylists(), db.playlistDao().getAllPlaylistMappings())
        }
        playlists = newPlaylists
        playlistMappings = newMappings
    }

    fun addSongToPlaylist(playlistId: Long, songId: Long, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val db = MusicDatabase.getDatabase(getApplication())
                val maxPos = db.playlistDao().getMaxPosition(playlistId) ?: 0
                db.playlistDao().addSongToPlaylist(
                    PlaylistSong(playlistId, songId, position = maxPos + 1)
                )
            }
            awaitLoadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val db = MusicDatabase.getDatabase(getApplication())
                val maxPos = db.playlistDao().getMaxPosition(playlistId) ?: 0
                val playlistSongs = songIds.mapIndexed { index, id ->
                    PlaylistSong(playlistId, id, position = maxPos + 1 + index)
                }
                db.playlistDao().addSongsToPlaylist(playlistSongs)
            }
            awaitLoadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun savePlaylistOrder(playlistId: Long, orderedSongIds: List<Long>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val db = MusicDatabase.getDatabase(getApplication())
                val dao = db.playlistDao()
                val mappings = dao.getPlaylistSongs(playlistId)
                val mappingBySongId = mappings.associateBy { it.songId }
                val updatedMappings = orderedSongIds.mapIndexedNotNull { index, songId ->
                    mappingBySongId[songId]?.copy(position = index)
                }
                if (updatedMappings.isNotEmpty()) {
                    dao.updatePlaylistSongs(updatedMappings)
                }
            }
            awaitLoadPlaylists()
            notifyPlaylistsChanged()
        }
    }

    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
                db.playlistDao().removeSongsFromPlaylist(playlistId, songIds)
            }
            awaitLoadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun removeSongFromPlaylist(playlistId: Long, songId: Long, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().removeSongFromPlaylist(playlistId, songId)
            }
            awaitLoadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun getPlaylistsContainingSong(songId: Long, callback: (List<Long>) -> Unit) {
        viewModelScope.launch {
            // This is a bit inefficient but works for now: check all playlists
            val all = withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().getAllPlaylists()
            }
            val matches = mutableListOf<Long>()
            for (p in all) {
                val sIds = withContext(Dispatchers.IO) {
                    com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().getSongIdsForPlaylist(p.id)
                }
                if (sIds.contains(songId)) {
                    matches.add(p.id)
                }
            }
            callback(matches)
        }
    }

    fun getSongsForPlaylist(playlistId: Long, callback: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().getSongIdsForPlaylist(playlistId)
            }
            callback(allSongs.filter { it.id in ids })
        }
    }

    fun getSongsForPlaylistSync(playlistId: Long): List<Song> {
        val songIds = playlistMappings.filter { it.playlistId == playlistId }
            .sortedWith(compareBy({ it.position }, { it.addedAt }))
            .map { it.songId }
        val songMap = allSongs.associateBy { it.id }
        return songIds.mapNotNull { songMap[it] }
    }

    fun deletePlaylist(playlist: com.demonlab.lune.data.Playlist, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().deletePlaylist(playlist)
            }
            loadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
                val playlist = db.playlistDao().getAllPlaylists().find { it.id == playlistId }
                if (playlist != null) {
                    db.playlistDao().updatePlaylist(playlist.copy(name = newName))
                }
            }
            loadPlaylists()
            notifyPlaylistsChanged()
            onComplete?.invoke()
        }
    }

    fun getPlaylistPreviewCovers(playlistId: Long, callback: (List<String?>) -> Unit) {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) {
                com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication()).playlistDao().getSongIdsForPlaylist(playlistId)
            }
            val covers = allSongs.filter { it.id in ids.take(4) }.map { it.coverUrl ?: it.albumArtUri?.toString() }
            callback(covers)
        }
    }

    private fun observeStats() {
        viewModelScope.launch {
            val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
            val dao = db.playbackStatsDao()
            
            launch {
                dao.getTopByCountFlow("SONG", 50).collect { topSongStats = it }
            }
            launch {
                dao.getTopByTimeFlow("PLAYLIST", 10).collect { topPlaylistStats = it }
            }
            launch {
                dao.getTopByCountFlow("ARTIST", 50).collect { topArtistStats = it }
            }
            launch {
                dao.getTopByCountFlow("GENRE", 10).collect { topGenreStats = it }
            }
        }
    }

    fun prepareDeleteSong(song: Song) {
        if (!visuallyDeletedIds.contains(song.id)) {
            visuallyDeletedIds.add(song.id)
        }
    }

    fun undoDeleteSong(song: Song) {
        visuallyDeletedIds.remove(song.id)
    }

    private data class PendingDelete(val songId: Long)
    private var pendingDelete: PendingDelete? = null

    private fun removeOverrideFor(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val db = com.demonlab.lune.data.MusicDatabase.getDatabase(getApplication())
                val override = db.songOverrideDao().getOverrideForSong(songId)
                if (override != null) db.songOverrideDao().deleteOverride(override)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Deletes the audio file. Android only lets an app delete media it does not own after the user approves it, so when
     * the direct attempts fail [requestApproval] receives the system confirmation to show (Android 10+); the result
     * comes back through [onDeleteApprovalResult]. Until a delete really happens the song is restored to the list.
     */
    fun deleteSongPermanently(
        songId: Long,
        songData: String,
        songUri: android.net.Uri,
        requestApproval: (android.content.IntentSender) -> Unit
    ) {
        val context = getApplication<Application>()
        val settingsManager = SettingsManager.getInstance(context)
        viewModelScope.launch {
            var approval: android.content.IntentSender? = null
            val deleted = withContext(Dispatchers.IO) {
                var success = false
                // A delete only counts when the file is really gone: some devices remove just the MediaStore row
                // and leave the file, and the next scan then brings the song straight back.
                fun fileGone() = songData.isBlank() || !java.io.File(songData).exists()

                // 1. SAF, when the user picked a music folder with write access
                try {
                    val folderUriString = settingsManager.musicFolderUri
                    if (folderUriString != null) {
                        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(folderUriString))
                        if (tree != null && tree.canWrite()) {
                            val fileName = java.io.File(songData).name
                            if (tree.findFile(fileName)?.delete() == true) success = true
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (success && !fileGone()) success = false

                // 2. MediaStore, Android 10 and older. On Android 11+ this can drop the database row without removing the
                // file, so there the system delete request below is used instead.
                if (!success && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    try {
                        if (context.contentResolver.delete(songUri, null, null) > 0) success = true
                    } catch (e: SecurityException) {
                        if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q &&
                            e is android.app.RecoverableSecurityException
                        ) {
                            approval = e.userAction.actionIntent.intentSender
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (success && !fileGone()) success = false

                // 3. Plain file delete
                if (!success && approval == null) {
                    try {
                        val file = java.io.File(songData)
                        if (!file.exists() || file.delete()) success = !file.exists()
                        if (success) android.media.MediaScannerConnection.scanFile(context, arrayOf(songData), null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 4. Android 11+: ask the user to approve the deletion through the system dialog
                if (!success && approval == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try {
                        approval = MediaStore.createDeleteRequest(context.contentResolver, listOf(songUri)).intentSender
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                success
            }

            val sender = approval
            if (deleted) {
                removeOverrideFor(songId)
                loadSongs()
                visuallyDeletedIds.remove(songId)
            } else if (sender != null) {
                // Keep the song hidden until the user answers the system dialog.
                pendingDelete = PendingDelete(songId)
                requestApproval(sender)
            } else {
                visuallyDeletedIds.remove(songId)
            }
        }
    }

    /** Called with the outcome of the system delete confirmation. */
    fun onDeleteApprovalResult(approved: Boolean) {
        val pending = pendingDelete ?: return
        pendingDelete = null
        if (approved) {
            removeOverrideFor(pending.songId)
            loadSongs()
        }
        visuallyDeletedIds.remove(pending.songId)
    }

    private var mediaStoreObserver: ContentObserver? = null
    private var debounceJob: Job? = null

    private fun registerMediaStoreObserver() {
        val context = getApplication<Application>()
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                debounceJob?.cancel()
                debounceJob = viewModelScope.launch {
                    delay(2000)
                    syncSongsInternal()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        mediaStoreObserver = observer
    }

    init {
        registerMediaStoreObserver()
        loadSongs()
        loadPlaylists()
        observeStats()
    }

    override fun onCleared() {
        super.onCleared()
        mediaStoreObserver?.let {
            getApplication<Application>().contentResolver.unregisterContentObserver(it)
        }
    }
}


