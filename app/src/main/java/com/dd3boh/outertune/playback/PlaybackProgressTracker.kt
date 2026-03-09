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
) : Player.Listener {
    private val TAG = "PlaybackProgressTracker"
    
    private var trackingJob: Job? = null
    private var currentTrackingSongId: String? = null
    private var currentSentSongId: String? = null
    private var has50PercentTriggered = false
    private var has100PercentTriggered = false
    private var maxProgressReached = 0f // Track maximum progress to handle seeking

    /**
     * Start tracking when media item transitions
     */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        
        // Cancel previous tracking
        stopTracking()
        
        if (mediaItem == null) return
        
        val songId = mediaItem.mediaId
        Log.d(TAG, "Media item transition: $songId")
        
        // Check if this song is from "To Listen" playlist
        checkAndStartTracking(songId)
    }

    /**
     * Stop tracking when playback state changes to idle or ended
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        
        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            stopTracking()
        }
    }

    /**
     * Check if song is from "To Listen" playlist and start tracking
     */
    private fun checkAndStartTracking(songId: String) {
        Log.d(TAG, "🔍 Checking if song $songId should be tracked")
        trackingJob = CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // Check if song is in "To Listen" playlist
                val isInToListenPlaylist = database.isSongInPlaylist(
                    PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                    songId
                ) > 0
                
                Log.d(TAG, "📋 Song $songId in To Listen playlist: $isInToListenPlaylist")
                
                if (!isInToListenPlaylist) {
                    Log.d(TAG, "⏭️ Song $songId not in To Listen playlist, skipping tracking")
                    return@launch
                }

                // Get the SentSong record
                val sentSong = songSharingRepository.getSentSongBySongId(songId)
                
                if (sentSong == null) {
                    Log.w(TAG, "⚠️ No SentSong record found for $songId (might be manually added)")
                    return@launch
                }

                // Start tracking this song
                currentTrackingSongId = songId
                currentSentSongId = sentSong.id
                has50PercentTriggered = sentSong.listenedAt != null
                has100PercentTriggered = false
                maxProgressReached = 0f // Reset max progress
                
                Log.d(TAG, "✅ Started tracking song: ${sentSong.songTitle} from ${sentSong.fromUsername}")
                Log.d(TAG, "   - SentSong ID: ${sentSong.id}")
                Log.d(TAG, "   - 50% already triggered: $has50PercentTriggered")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking song for tracking", e)
            }
        }
    }

    /**
     * Track playback progress (called periodically by MusicService)
     * @param player The ExoPlayer instance
     */
    fun trackProgress(player: Player, scope: CoroutineScope) {
        if (currentTrackingSongId == null || currentSentSongId == null) return
        
        scope.launch {
            try {
                val currentPosition = player.currentPosition
                val duration = player.duration
                
                if (duration <= 0) {
                    Log.d(TAG, "Duration is 0 or negative, skipping progress check")
                    return@launch
                }
                
                val progressPercent = (currentPosition.toFloat() / duration.toFloat()) * 100
                
                // Update max progress reached (handles seeking backwards)
                if (progressPercent > maxProgressReached) {
                    maxProgressReached = progressPercent
                }
                
                Log.v(TAG, "Tracking progress: ${progressPercent.toInt()}% (max: ${maxProgressReached.toInt()}%) for song $currentTrackingSongId")
                
                // Check 50% milestone using max progress
                if (!has50PercentTriggered && maxProgressReached >= 50f) {
                    has50PercentTriggered = true
                    Log.d(TAG, "🎯 50% milestone triggered for song $currentTrackingSongId (max progress: ${maxProgressReached.toInt()}%)")
                    handle50PercentMilestone()
                }
                
                // Check 100% completion using max progress (use 95% to catch it before song ends)
                if (!has100PercentTriggered && maxProgressReached >= 95f) {
                    has100PercentTriggered = true
                    Log.d(TAG, "🎯 100% completion triggered for song $currentTrackingSongId (max progress: ${maxProgressReached.toInt()}%)")
                    handle100PercentCompletion()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking progress", e)
            }
        }
    }

    /**
     * Handle 50% milestone - notify sender
     */
    private suspend fun handle50PercentMilestone() {
        val sentSongId = currentSentSongId ?: return
        val songId = currentTrackingSongId ?: return
        
        try {
            Log.d(TAG, "🎉 50% milestone reached for song $songId (SentSong ID: $sentSongId)")
            
            // Mark as listened in Firebase
            songSharingRepository.markSongAsListened(sentSongId)
            
            Log.d(TAG, "✅ Marked song as listened in Firebase")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling 50% milestone", e)
        }
    }

    /**
     * Handle 100% completion - remove from playlist
     */
    private suspend fun handle100PercentCompletion() {
        val sentSongId = currentSentSongId ?: return
        val songId = currentTrackingSongId ?: return
        
        try {
            Log.d(TAG, "🏁 100% completion reached for song $songId (SentSong ID: $sentSongId)")
            
            // Mark as completed and remove from "To Listen" playlist
            songSharingRepository.markSongAsCompleted(sentSongId, songId)
            
            Log.d(TAG, "✅ Marked song as completed and removed from playlist")
            
            // Stop tracking
            stopTracking()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling 100% completion", e)
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
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopTracking()
    }
}
