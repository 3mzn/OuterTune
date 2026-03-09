package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.SpotifyApiTokenKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.sync.ImportResult
import com.dd3boh.outertune.sync.JsonTrack
import com.dd3boh.outertune.sync.SpotifyRepository
import com.dd3boh.outertune.sync.SpotifyTrack
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val spotifyRepository: SpotifyRepository,
    private val syncUtils: SyncUtils,
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _failedImports = MutableStateFlow<List<ImportResult.Failed>>(emptyList())
    val failedImports: StateFlow<List<ImportResult.Failed>> = _failedImports.asStateFlow()

    // Job tracking for cancellation
    private var currentImportJob: kotlinx.coroutines.Job? = null

    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun exchangeCodeForToken(code: String): String? {
        return spotifyRepository.getAccessTokenFromCode(code)
    }

    fun startSpotifyApiSync() {
        viewModelScope.launch {
            val apiToken = context.dataStore.get(SpotifyApiTokenKey, "")
            if (apiToken.isEmpty()) {
                _syncState.value = SyncState.Error("Please login via Spotify API first")
                return@launch
            }

            _syncState.value = SyncState.Syncing()
            _statusText.value = "Fetching liked songs from Spotify API..."
            
            val spotifyTracks = spotifyRepository.getAllLikedSongsApi(apiToken) { count ->
                _statusText.value = "Fetched $count songs from Spotify API..."
            }

            if (spotifyTracks.isEmpty()) {
                _syncState.value = SyncState.Success
                _statusText.value = "No liked songs found on Spotify"
                return@launch
            }

            performSync(spotifyTracks)
        }
    }

    private suspend fun performSync(spotifyTracks: List<SpotifyTrack>) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Matching songs with YouTube Music..."
            var songsSynced = 0
            val totalSongs = spotifyTracks.size
            spotifyTracks.forEachIndexed { index, track ->
                try {
                    _progress.value = index.toFloat() / totalSongs
                    _statusText.value = "Matching [${index + 1}/$totalSongs]: ${track.name}"

                    val matchedSong = matchSpotifyTrack(track)
                    if (matchedSong != null) {
                        val metadata = matchedSong.toMediaMetadata()
                        val existing = database.song(matchedSong.id).firstOrNull()
                        if (existing == null) {
                            database.insert(mediaMetadata = metadata) { it.localToggleLike() }
                        } else if (!existing.song.liked) {
                            database.update(song = existing.song.localToggleLike())
                        }
                        songsSynced++
                        _syncState.value = SyncState.Syncing(songsSynced, totalSongs, matchedSong.title)
                    }
                } catch (e: Exception) {
                    // Log error but continue with next song
                    _statusText.value = "Error matching ${track.name}: ${e.message}"
                }
            }

            _syncState.value = SyncState.Success
            _statusText.value = "Sync complete! Matched $songsSynced songs."
        }
    }

    private suspend fun matchSpotifyTrack(track: SpotifyTrack): SongItem? {
        val query = "${track.name} ${track.artists.joinToString(" ") { it.name }}"
        return YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>().firstOrNull()
        }.getOrNull()
    }

    /**
     * Import playlist from JSON file
     * @param uri URI of the JSON file
     * @param playlistName Name for the playlist (will create or append)
     */
    fun startJsonImport(uri: Uri, playlistName: String) {
        // Cancel any existing import
        currentImportJob?.cancel()
        
        currentImportJob = viewModelScope.launch {
            try {
                _syncState.value = SyncState.Syncing()
                _statusText.value = "Reading JSON file..."
                _failedImports.value = emptyList()

                // Read and parse JSON
                val tracks = parseJsonFile(uri)
                if (tracks.isEmpty()) {
                    _syncState.value = SyncState.Error("No tracks found in JSON file")
                    return@launch
                }

                _statusText.value = "Found ${tracks.size} tracks in JSON"

                // Find or create playlist
                val playlistId = withContext(Dispatchers.IO) {
                    findOrCreatePlaylist(playlistName)
                }

                // Import tracks
                performJsonImport(tracks, playlistId, playlistName)

            } catch (e: kotlinx.coroutines.CancellationException) {
                _syncState.value = SyncState.Cancelled
                _statusText.value = "Import cancelled"
                throw e // Re-throw to properly cancel coroutine
            } catch (e: SerializationException) {
                _syncState.value = SyncState.Error("Invalid JSON format: ${e.message}")
            } catch (e: Exception) {
                _syncState.value = SyncState.Error("Import failed: ${e.message}")
            }
        }
    }

    /**
     * Cancel ongoing JSON import
     */
    fun cancelJsonImport() {
        currentImportJob?.cancel()
        currentImportJob = null
        _syncState.value = SyncState.Cancelled
        _statusText.value = "Import cancelled"
    }

    /**
     * Parse JSON file from URI
     */
    private suspend fun parseJsonFile(uri: Uri): List<JsonTrack> = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open file")
        
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        
        // Parse as array of tracks
        json.decodeFromString<List<JsonTrack>>(jsonString)
    }

    /**
     * Find existing playlist by name or create new one
     */
    private suspend fun findOrCreatePlaylist(playlistName: String): String {
        return withContext(Dispatchers.IO) {
            // Query all playlists and find by name
            val existingPlaylist = database.playlists(
                filter = com.dd3boh.outertune.constants.PlaylistFilter.LIBRARY,
                sortType = com.dd3boh.outertune.constants.PlaylistSortType.CREATE_DATE,
                descending = false
            ).firstOrNull()?.find { it.playlist.name == playlistName }

            if (existingPlaylist != null) {
                _statusText.value = "Found existing playlist: $playlistName"
                existingPlaylist.id
            } else {
                _statusText.value = "Creating new playlist: $playlistName"
                val newPlaylist = PlaylistEntity(
                    name = playlistName,
                    browseId = null,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    isLocal = true
                )
                database.query {
                    insert(newPlaylist)
                }
                newPlaylist.id
            }
        }
    }

    /**
     * Import tracks and add to playlist with retry logic, duplicate detection, and error handling
     */
    private suspend fun performJsonImport(
        tracks: List<JsonTrack>,
        playlistId: String,
        playlistName: String
    ) {
        withContext(Dispatchers.IO) {
            _statusText.value = "Matching songs with YouTube Music..."
            var songsImported = 0
            var songsSkipped = 0
            val totalSongs = tracks.size
            val failed = mutableListOf<ImportResult.Failed>()

            tracks.forEachIndexed { index, track ->
                // Check for cancellation
                if (!currentImportJob?.isActive!!) {
                    return@withContext
                }

                try {
                    _progress.value = index.toFloat() / totalSongs
                    _statusText.value = "Matching [${index + 1}/$totalSongs]: ${track.displayName()}"

                    // Match with retry logic
                    val matchedSong = matchJsonTrackWithRetry(track, maxAttempts = 2)
                    
                    if (matchedSong != null) {
                        // Check for duplicates
                        val isDuplicate = database.isSongInPlaylist(playlistId, matchedSong.id) > 0
                        
                        if (isDuplicate) {
                            songsSkipped++
                            _statusText.value = "Skipped duplicate [${index + 1}/$totalSongs]: ${track.displayName()}"
                        } else {
                            val metadata = matchedSong.toMediaMetadata()
                            
                            // Insert song into library if not exists
                            val existing = database.song(matchedSong.id).firstOrNull()
                            if (existing == null) {
                                try {
                                    database.insert(mediaMetadata = metadata)
                                } catch (e: Exception) {
                                    failed.add(ImportResult.Failed(track, "Database error: ${e.message}"))
                                    return@forEachIndexed
                                }
                            }

                            // Add to playlist
                            try {
                                val playlist = database.playlist(playlistId).firstOrNull()
                                if (playlist != null) {
                                    database.query {
                                        addSongToPlaylist(playlist, listOf(matchedSong.id))
                                    }
                                    songsImported++
                                    _syncState.value = SyncState.Syncing(songsImported, totalSongs, matchedSong.title)
                                } else {
                                    failed.add(ImportResult.Failed(track, "Playlist not found"))
                                }
                            } catch (e: Exception) {
                                failed.add(ImportResult.Failed(track, "Failed to add to playlist: ${e.message}"))
                            }
                        }
                    } else {
                        failed.add(ImportResult.Failed(track, "No match found on YouTube Music after retries"))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Re-throw cancellation
                } catch (e: Exception) {
                    failed.add(ImportResult.Failed(track, "Unexpected error: ${e.message}"))
                    _statusText.value = "Error processing ${track.displayName()}: ${e.message}"
                }
            }

            _failedImports.value = failed
            _syncState.value = SyncState.Success
            
            // Build status message
            val parts = mutableListOf<String>()
            if (songsImported > 0) parts.add("Added $songsImported songs")
            if (songsSkipped > 0) parts.add("skipped $songsSkipped duplicates")
            if (failed.size > 0) parts.add("${failed.size} failed")
            
            _statusText.value = "Import complete! ${parts.joinToString(", ")} to \"$playlistName\"."
        }
    }

    /**
     * Match JSON track with YouTube Music with retry logic
     * @param track Track to match
     * @param maxAttempts Maximum number of attempts (default 2 = 1 retry)
     * @return Matched SongItem or null if all attempts fail
     */
    private suspend fun matchJsonTrackWithRetry(track: JsonTrack, maxAttempts: Int = 2): SongItem? {
        repeat(maxAttempts) { attempt ->
            try {
                val result = matchJsonTrack(track)
                if (result != null) {
                    return result
                }
                // If no result but no error, wait before retry
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Don't retry on cancellation
            } catch (e: Exception) {
                // Network or other error - retry if attempts remain
                if (attempt == maxAttempts - 1) {
                    // Last attempt failed, return null
                    return null
                }
                kotlinx.coroutines.delay(500)
            }
        }
        return null
    }

    /**
     * Match JSON track with YouTube Music (single attempt)
     */
    private suspend fun matchJsonTrack(track: JsonTrack): SongItem? {
        val query = track.toSearchQuery()
        return YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items.filterIsInstance<SongItem>().firstOrNull()
        }.getOrNull()
    }

    /**
     * Clear failed imports list
     */
    fun clearFailedImports() {
        _failedImports.value = emptyList()
    }

    sealed class SyncState {
        data object Idle : SyncState()
        data class Syncing(val current: Int = 0, val total: Int = 0, val lastSong: String = "") : SyncState()
        data object Success : SyncState()
        data object Cancelled : SyncState()
        data class Error(val message: String) : SyncState()
    }
}
