package com.dd3boh.outertune.viewmodels

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.social.SongSharingRepository
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ViewModel for handling real-time "friend listened" notifications when app is open
 */
@HiltViewModel
class SongListenedNotificationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songSharingRepository: SongSharingRepository,
    private val auth: FirebaseAuth
) : ViewModel() {
    private val TAG = "SongListenedNotifVM"

    private var listenerJob: Job? = null
    private var currentUserId: String? = null

    private var authListener: FirebaseAuth.AuthStateListener? = null

    init {
        Log.d(TAG, "🚀 SongListenedNotificationViewModel initialized")
        
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
        listenerJob = viewModelScope.launch {
            try {
                songSharingRepository.observeListenedSongsNeedingNotification(
                    fromUid = currentUid,
                    since = thirtyDaysAgo
                ).collect { songs ->
                    Log.d(TAG, "📬 Received ${songs.size} songs needing notification")
                    
                    // Show notification for each song
                    songs.forEach { sentSong ->
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

    override fun onCleared() {
        super.onCleared()
        authListener?.let { auth.removeAuthStateListener(it) }
        stopListening()
    }
}
