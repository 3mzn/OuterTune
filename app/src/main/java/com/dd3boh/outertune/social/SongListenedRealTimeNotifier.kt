package com.dd3boh.outertune.social

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton service for real-time "friend listened" notifications
 * This runs alongside the app and monitors Firestore for songs that have been listened to
 */
@Singleton
class SongListenedRealTimeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songSharingRepository: SongSharingRepository,
    private val auth: FirebaseAuth
) {
    private val TAG = "SongListenedRealTime"
    private var listenerJob: Job? = null
    private var currentUserId: String? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        Log.d(TAG, "🚀 SongListenedRealTimeNotifier initialized")
        
        // Monitor Firebase Auth state changes
        authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val newUserId = firebaseAuth.currentUser?.uid
            
            if (newUserId != currentUserId) {
                Log.d(TAG, "🔄 User changed from $currentUserId to $newUserId")
                currentUserId = newUserId
                
                if (newUserId != null) {
                    Log.d(TAG, "✅ User logged in: $newUserId - starting notification listener")
                    startListening()
                } else {
                    Log.w(TAG, "⚠️ User logged out - stopping notification listener")
                    stopListening()
                }
            }
        }.also { 
            auth.addAuthStateListener(it)
        }
    }

    /**
     * Start listening for songs that have been listened to
     */
    private fun startListening() {
        // Cancel any existing listener
        listenerJob?.cancel()
        
        val currentUid = currentUserId ?: return
        val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        
        Log.d(TAG, "👂 Starting real-time listener for listened songs")
        listenerJob = scope.launch {
            try {
                songSharingRepository.observeListenedSongsNeedingNotification(
                    fromUid = currentUid,
                    since = thirtyDaysAgo
                ).collect { songs ->
                    Log.d(TAG, "📬 Received ${songs.size} songs needing notification")
                    
                    // Only notify for songs listened to in the last 24 hours
                    val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                    val recentSongs = songs.filter { song ->
                        song.listenedAt != null && song.listenedAt > oneDayAgo
                    }
                    
                    Log.d(TAG, "🔔 Filtering to ${recentSongs.size} recent songs (last 24h)")
                    
                    // Show notification for each recent song
                    recentSongs.forEach { sentSong ->
                        Log.d(TAG, "🔔 Showing notification for: ${sentSong.songTitle}")
                        com.dd3boh.outertune.utils.SongNotificationHelper.showNotification(context, sentSong)
                        
                        // Mark as notified
                        songSharingRepository.markNotificationSent(sentSong.id)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in notification listener", e)
            }
        }
    }

    /**
     * Stop listening for notifications
     */
    private fun stopListening() {
        Log.d(TAG, "🛑 Stopping notification listener")
        listenerJob?.cancel()
        listenerJob = null
    }

    /**
     * Cleanup when destroyed
     */
    fun destroy() {
        authListener?.let { auth.removeAuthStateListener(it) }
        stopListening()
    }
}