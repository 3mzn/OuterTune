package com.dd3boh.outertune.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.social.SongSharingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks playback progress for songs from "To Listen" playlist
 * Handles 50% milestone notification and 100% auto-deletion
 */
@Singleton
class PlaybackProgressTracker @Inject constructor(
    private val songSharingRepository: SongSharingRepository,
    private val database: MusicDatabase
) {
    private val TAG = "PlaybackProgressTracker"
    
    private var trackingJob: Job? = null
    var currentTrackingSongId: String? = null
    var currentSentSongId: String? = null
    private var lastFailedSongId: String? = null // Prevent hammering Firestore for non-shared songs
    private var has50PercentTriggered = false
    private var has100PercentTriggered = false
    private var maxProgressReached = 0f // Track maximum progress to handle seeking
    private var lastHeartbeatProgress = -1 // Track last 10% milestone for logging
    private val trackingInitialized = AtomicBoolean(false) // Atomic flag for initialization state

    /**
     * Start tracking when media item transitions
     * @param mediaItem The media item being played
     * @param reason The reason for the transition
     * @param currentPlaylistId The playlist ID currently being played from (null if not from a playlist)
     */
    fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int, currentPlaylistId: String?) {
        // Cancel previous tracking
        stopTracking()
        
        // CRITICAL: Always reset failure blacklist on a new song transition
        // This ensures a fresh device (or role-switched device) gets a new chance to check Firestore
        lastFailedSongId = null
        
        if (mediaItem == null) return
        
        val songId = mediaItem.mediaId
        Log.d(TAG, "Media item transition: $songId from playlist: $currentPlaylistId")
        
        // Check if this song is from "To Listen" playlist
        checkAndStartTracking(songId, currentPlaylistId)
    }

    /**
     * Stop tracking when playback state changes to idle or ended
     * Called manually from MusicService
     */
    fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            stopTracking()
            // Reset failure state when stopping to allow fresh start later
            lastFailedSongId = null
        }
    }

    /**
     * Check if song is from "To Listen" playlist and start tracking
     * @param songId The song ID to check
     * @param currentPlaylistId The playlist ID currently being played from (null if not from a playlist)
     */
    private fun checkAndStartTracking(songId: String, currentPlaylistId: String?) {
        // CRITICAL: Only track if playing FROM "To Listen" playlist
        Log.d(TAG, "🏁 Context check: PlaylistId='$currentPlaylistId' | Expected='${PlaylistEntity.TO_LISTEN_PLAYLIST_ID}'")
        
        if (currentPlaylistId != PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
            Log.v(TAG, "⏭️ Not playing from To Listen playlist ($currentPlaylistId), skipping tracking.")
            return
        }

        if (songId == lastFailedSongId) {
            Log.v(TAG, "⏭️ Song $songId already failed check, skipping re-check.")
            return
        }
        
        Log.i(TAG, "🚀 [checkAndStartTracking] Starting async check for song: $songId")
        
        trackingJob = CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "NotLoggedIn"
                Log.i(TAG, "🔍 [User: $uid] Checking tracking for $songId in playlist $currentPlaylistId")
                
                // Double-check: Verify song is actually in "To Listen" playlist locally
                val isInToListenPlaylist = database.isSongInPlaylistSync(
                    PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                    songId
                ) > 0
                
                Log.i(TAG, "📋 [Local DB Check] Song $songId in To Listen playlist: $isInToListenPlaylist")
                
                if (!isInToListenPlaylist) {
                    Log.i(TAG, "⏭️ Song $songId not in local To Listen playlist, skipping track.")
                    lastFailedSongId = songId
                    return@launch
                }

                // Get the SentSong record from Firestore
                Log.i(TAG, "☁️ [Firestore Check] Querying for SentSong document for $songId...")
                val sentSong = songSharingRepository.getSentSongBySongId(songId)
                
                if (sentSong == null) {
                    Log.i(TAG, "⚠️ No pending SentSong document found for $songId (User: $uid).")
                    lastFailedSongId = songId
                    return@launch
                }

                // Start tracking this song
                // Always start with false for a fresh playback session
                currentTrackingSongId = songId
                currentSentSongId = sentSong.id
                lastFailedSongId = null
                has50PercentTriggered = false
                has100PercentTriggered = false
                maxProgressReached = 0f
                lastHeartbeatProgress = -1
                
                Log.i(TAG, "✅ [Tracking Start] Song: '${sentSong.songTitle}' | Doc: ${sentSong.id} | Firestore listenedAt: ${sentSong.listenedAt} | Starting fresh tracking")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking song for tracking", e)
                e.printStackTrace()
            }
        }
    }

    /**
     * Track playback progress (called periodically by MusicService)
     * @param player The ExoPlayer instance
     * @param scope Coroutine scope
     * @param currentPlaylistId The playlist ID currently being played from (equivalent to QueueBoard.title)
     */
    fun trackProgress(player: Player, scope: CoroutineScope, currentPlaylistId: String?) {
        val mediaId = player.currentMediaItem?.mediaId
        
        // DIAGNOSTIC: Log every call to trackProgress
        if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
            Log.v(TAG, "🔍 [trackProgress] Called | PlaylistId: $currentPlaylistId | MediaId: $mediaId | Tracking: ${currentTrackingSongId != null} | Playing: ${player.isPlaying}")
        }
        
        // If we ARE in the right playlist but tracking isn't active, try to (re)start it
        if (currentTrackingSongId == null || currentSentSongId == null) {
            if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID && !trackingInitialized.get() && mediaId != null) {
                // If the current media ID isn't the one that explicitly failed recently, try again
                if (mediaId != lastFailedSongId) {
                    // CRITICAL: Set flag BEFORE launching coroutine to prevent race condition
                    trackingInitialized.set(true)
                    Log.i(TAG, "🔄 Tracking is inactive but we're in the right playlist. Attempting to start...")
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            checkAndStartTracking(mediaId, currentPlaylistId)
                        } finally {
                            // Reset flag when done, but only if we didn't successfully initialize
                            // This allows retry if initialization failed
                            if (currentSentSongId == null) {
                                trackingInitialized.set(false)
                            }
                        }
                    }
                } else {
                    Log.v(TAG, "⏭️ [trackProgress] Skipping re-check for failed song: $mediaId")
                }
            } else {
                if (currentPlaylistId == PlaylistEntity.TO_LISTEN_PLAYLIST_ID) {
                    Log.v(TAG, "⏭️ [trackProgress] Not starting: trackingInitialized=${trackingInitialized.get()}, mediaId=$mediaId, lastFailed=$lastFailedSongId")
                }
            }
            return
        }

        // Verify we are still on the same song
        if (mediaId != currentTrackingSongId) {
            Log.i(TAG, "⚠️ Tracking mismatch: Player:$mediaId vs Tracker:$currentTrackingSongId. Stopping.")
            stopTracking()
            return
        }

        // CRITICAL FIX: Access player properties on Main thread, then do calculations
        // Player MUST be accessed on the Main thread
        try {
            val currentPosition = player.currentPosition
            val duration = player.duration
            
            if (duration <= 0) return // Wait for duration to load
            
            val progressPercent = (currentPosition.toFloat() / duration.toFloat()) * 100
            
            if (progressPercent > maxProgressReached) {
                maxProgressReached = progressPercent
            }
            
            // Heartbeat log every 10%
            val intProgress = maxProgressReached.toInt()
            if (intProgress / 10 > lastHeartbeatProgress) {
                lastHeartbeatProgress = intProgress / 10
                Log.i(TAG, "💓 [Heartbeat] ${intProgress}% | Pos: ${currentPosition/1000}s / ${duration/1000}s | ID: $currentTrackingSongId | 50%Triggered: $has50PercentTriggered | 100%Triggered: $has100PercentTriggered")
            }
            
            // Milestone triggers (Mutually exclusive check)
            // Set flags SYNCHRONOUSLY, then launch async Firestore operations
            Log.v(TAG, "🎯 [Milestone Check] maxProgress: $maxProgressReached | 50%: $has50PercentTriggered | 100%: $has100PercentTriggered")
            
            if (!has100PercentTriggered && maxProgressReached >= 95f) {
                has100PercentTriggered = true
                has50PercentTriggered = true // Ensure both are marked if 95% is reached quickly
                Log.i(TAG, "🏁 95% Completion Triggered for $currentTrackingSongId")
                // Use independent IO scope to avoid Main dispatcher issues
                CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    handle100PercentCompletion()
                }
            } 
            else if (!has50PercentTriggered && maxProgressReached >= 50f) {
                has50PercentTriggered = true
                Log.i(TAG, "🎯 50% Milestone Triggered for $currentTrackingSongId")
                // Use independent IO scope to avoid Main dispatcher issues
                CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    handle50PercentMilestone()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Tracking loop error", e)
        }
    }

    /**
     * Handle 50% milestone - notify sender
     * 
     * CRITICAL: This handler implements idempotent updates to prevent race conditions.
     * Multiple devices/sessions might reach 50% simultaneously, so we re-check Firestore
     * before updating to ensure only the first one writes.
     * 
     * Also implements retry logic: if currentSentSongId is null (async initialization
     * hasn't completed yet), retry synchronously to ensure milestone is handled.
     */
    private suspend fun handle50PercentMilestone() {
        var sentSongId = currentSentSongId
        val songId = currentTrackingSongId ?: return
        
        // RETRY LOGIC: If currentSentSongId is null, async initialization hasn't completed.
        // Retry synchronously to ensure we don't miss the milestone.
        if (sentSongId == null) {
            Log.w(TAG, "⚠️ [50% Handler] currentSentSongId is null, retrying initialization...")
            // Reset trackingInitialized to allow retry
            trackingInitialized.set(false)
            // Retry synchronously on IO dispatcher
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                checkAndStartTracking(songId, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
            sentSongId = currentSentSongId
            if (sentSongId == null) {
                Log.e(TAG, "❌ [50% Handler] Retry failed, currentSentSongId still null")
                return
            }
            Log.i(TAG, "✅ [50% Handler] Retry succeeded, currentSentSongId: $sentSongId")
        }
        
        try {
            Log.i(TAG, "📡 [50% Handler] Starting Firestore update for $sentSongId (songId: $songId)")
            
            // CRITICAL: Update Firestore directly using the document ID we have
            // Do NOT re-query because getSentSongBySongId() can return stale/wrong documents
            Log.i(TAG, "✍️ [50% Handler] Proceeding with Firestore update using document ID: $sentSongId")
            songSharingRepository.markSongAsListened(sentSongId)
            Log.i(TAG, "✅ [50% Handler] Firestore update completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [50% Handler] Error handling 50% milestone", e)
            e.printStackTrace()
        }
    }

    /**
     * Handle 100% completion - remove from playlist
     * 
     * CRITICAL: This handler implements idempotent updates to prevent race conditions.
     * Multiple devices/sessions might reach 95% simultaneously, so we re-check Firestore
     * before updating to ensure only the first one writes.
     * 
     * Also implements retry logic: if currentSentSongId is null (async initialization
     * hasn't completed yet), retry synchronously to ensure completion is handled.
     */
    private suspend fun handle100PercentCompletion() {
        var sentSongId = currentSentSongId
        val songId = currentTrackingSongId ?: return
        
        // RETRY LOGIC: If currentSentSongId is null, async initialization hasn't completed.
        // Retry synchronously to ensure we don't miss the completion.
        if (sentSongId == null) {
            Log.w(TAG, "⚠️ [100% Handler] currentSentSongId is null, retrying initialization...")
            // Reset trackingInitialized to allow retry
            trackingInitialized.set(false)
            // Retry synchronously on IO dispatcher
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                checkAndStartTracking(songId, PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
            sentSongId = currentSentSongId
            if (sentSongId == null) {
                Log.e(TAG, "❌ [100% Handler] Retry failed, currentSentSongId still null")
                stopTracking()
                return
            }
            Log.i(TAG, "✅ [100% Handler] Retry succeeded, currentSentSongId: $sentSongId")
        }
        
        try {
            Log.i(TAG, "📡 [100% Handler] Starting Firestore update and local removal for $sentSongId (songId: $songId)")
            
            // CRITICAL: Update Firestore directly using the document ID we have
            // Do NOT re-query because getSentSongBySongId() can return stale/wrong documents
            Log.i(TAG, "✍️ [100% Handler] Proceeding with Firestore update using document ID: $sentSongId")
            
            // Always attempt local removal (idempotent - Room handles duplicates gracefully)
            // This handles both local removal and Firestore update
            songSharingRepository.markSongAsCompleted(sentSongId, songId)
            Log.i(TAG, "✅ [100% Handler] Firestore update and local removal completed successfully")
            
            stopTracking()
        } catch (e: Exception) {
            Log.e(TAG, "❌ [100% Handler] Error handling 100% completion", e)
            e.printStackTrace()
            stopTracking()
        }
    }

    /**
     * Stop tracking current song
     */
    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        currentTrackingSongId = null
        currentSentSongId = null
        has50PercentTriggered = false
        has100PercentTriggered = false
        maxProgressReached = 0f
        lastHeartbeatProgress = -1
        trackingInitialized.set(false)  // CRITICAL: Reset initialization flag for next song
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopTracking()
        lastFailedSongId = null
    }

    /**
     * Clear the blacklist when user performs a seek operation.
     * This allows the song to be re-checked for tracking after seeking.
     */
    fun onSeekPerformed() {
        Log.i(TAG, "🔄 [Seek] User performed seek, clearing blacklist for song: $currentTrackingSongId")
        lastFailedSongId = null
        // Also reset tracking state to allow fresh tracking after seek
        trackingInitialized.set(false)
    }

    /**
     * Clear the blacklist when user restarts playback (replay).
     * This allows the song to be re-checked for tracking after replay.
     */
    fun onPlaybackRestarted() {
        Log.i(TAG, "🔄 [Replay] User restarted playback, clearing blacklist for song: $currentTrackingSongId")
        lastFailedSongId = null
        // Also reset tracking state to allow fresh tracking after replay
        trackingInitialized.set(false)
    }
}
