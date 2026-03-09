package com.dd3boh.outertune.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.social.SentSong
import com.dd3boh.outertune.social.SongSharingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SongSharingViewModel @Inject constructor(
    private val songSharingRepository: SongSharingRepository,
    private val database: MusicDatabase,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : ViewModel() {
    private val TAG = "SongSharingViewModel"

    private val _incomingSongs = MutableStateFlow<List<SentSong>>(emptyList())
    val incomingSongs: StateFlow<List<SentSong>> = _incomingSongs.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private var listenerJob: kotlinx.coroutines.Job? = null

    private var currentUserId: String? = null

    init {
        Log.d(TAG, "🚀 SongSharingViewModel initialized")
        
        // Initialize immediately on creation
        viewModelScope.launch {
            try {
                // Create "To Listen" playlist if it doesn't exist
                Log.d(TAG, "📋 Creating To Listen playlist...")
                songSharingRepository.initializeToListenPlaylist()
                Log.d(TAG, "✅ To Listen playlist initialized")
                
                // Start listening for incoming songs
                Log.d(TAG, "👂 Starting to listen for incoming songs...")
                songSharingRepository.observeIncomingSongs().collect { songs ->
                    Log.d(TAG, "📬 Received ${songs.size} incoming songs from Firestore")
                    _incomingSongs.value = songs
                    
                    // Process new songs with proper coroutine handling
                    songs.forEach { sentSong ->
                        Log.d(TAG, "🎵 Processing song: ${sentSong.songTitle} from ${sentSong.fromUsername}")
                        // Launch each processing in a separate coroutine to handle errors independently
                        launch {
                            try {
                                processSentSong(sentSong)
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to process song ${sentSong.songTitle}", e)
                                // Continue processing other songs even if one fails
                            }
                        }
                    }
                }
                
                _isInitialized.value = true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error initializing song sharing feature", e)
            }
        }
    }

    /**
     * Stop listening for incoming songs
     */
    private fun stopListening() {
        Log.d(TAG, "🛑 Stopping listener")
        listenerJob?.cancel()
        listenerJob = null
        _incomingSongs.value = emptyList()
        _isInitialized.value = false
    }

    /**
     * Process a sent song - fetch metadata and add to "To Listen" playlist
     * Includes retry logic for transient failures
     */
    private suspend fun processSentSong(sentSong: SentSong) {
        try {
            Log.d(TAG, "🔍 Starting to process sent song: ${sentSong.songTitle}")
            
            // Check if already in playlist (duplicate check)
            val isDuplicate = database.isSongInPlaylist(
                com.dd3boh.outertune.db.entities.PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                sentSong.songId
            ) > 0
            
            if (isDuplicate) {
                Log.d(TAG, "⏭️ Song ${sentSong.songTitle} already in To Listen playlist, skipping")
                return
            }
            
            Log.d(TAG, "✅ Song ${sentSong.songTitle} is new, fetching metadata...")

            // Fetch song metadata from YouTube if needed
            val metadata = fetchSongMetadata(sentSong)
            
            if (metadata != null) {
                Log.d(TAG, "📝 Metadata fetched: ${metadata.title}")
                
                // Add to "To Listen" playlist with retry logic
                var retryCount = 0
                val maxRetries = 3
                var success = false
                
                while (retryCount < maxRetries && !success) {
                    try {
                        success = songSharingRepository.addSongToToListenPlaylist(sentSong, metadata)
                        
                        if (success) {
                            Log.d(TAG, "✅ Successfully added ${sentSong.songTitle} to To Listen playlist")
                        } else {
                            Log.w(TAG, "⚠️ Failed to add ${sentSong.songTitle} (might be duplicate)")
                        }
                    } catch (e: Exception) {
                        retryCount++
                        if (retryCount < maxRetries) {
                            val delayMs = 1000L * retryCount // Exponential backoff: 1s, 2s, 3s
                            Log.w(TAG, "⚠️ Attempt $retryCount failed, retrying in ${delayMs}ms: ${e.message}")
                            kotlinx.coroutines.delay(delayMs)
                        } else {
                            Log.e(TAG, "❌ Failed to add ${sentSong.songTitle} after $maxRetries attempts", e)
                            throw e
                        }
                    }
                }
            } else {
                Log.w(TAG, "⚠️ Could not fetch metadata for ${sentSong.songTitle}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing sent song ${sentSong.songTitle}", e)
            e.printStackTrace()
        }
    }

    /**
     * Fetch song metadata from YouTube or create from SentSong data
     */
    private suspend fun fetchSongMetadata(sentSong: SentSong): MediaMetadata? {
        return try {
            // Fallback: create from SentSong data (YouTube API call removed for simplicity)
            MediaMetadata(
                id = sentSong.songId,
                title = sentSong.songTitle,
                artists = listOf(
                    MediaMetadata.Artist(
                        id = null,
                        name = sentSong.songArtist
                    )
                ),
                duration = sentSong.songDuration,
                thumbnailUrl = sentSong.thumbnailUrl,
                album = null,
                genre = null,
                year = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching metadata for ${sentSong.songId}", e)
            null
        }
    }
}
