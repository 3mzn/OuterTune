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

    companion object {
        const val CHANNEL_ID = "song_listened_notifications"
        const val NOTIFICATION_ID_BASE = 3000
    }

    init {
        Log.d(TAG, "🚀 SongListenedNotificationViewModel initialized")
        
        // Monitor Firebase Auth state changes
        viewModelScope.launch {
            while (true) {
                val currentUser = auth.currentUser
                val newUserId = currentUser?.uid
                
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
                
                kotlinx.coroutines.delay(1000) // Check every second
            }
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
                        showNotification(sentSong)
                        
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
     * Show local notification
     */
    private fun showNotification(sentSong: com.dd3boh.outertune.social.SentSong) {
        createNotificationChannel()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create intent to open Social screen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "social")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Handle edge cases for missing data
        val friendName = sentSong.fromUsername.ifEmpty { "A friend" }
        val songTitle = sentSong.songTitle.ifEmpty { "a song you sent" }

        val title = context.getString(R.string.friend_listened_notification_title)
        val message = if (sentSong.songTitle.isEmpty()) {
            "$friendName listened to a song you sent"
        } else {
            "$friendName listened to $songTitle"
        }

        // Build notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Show notification with unique ID
        notificationManager.notify(
            NOTIFICATION_ID_BASE + sentSong.id.hashCode(),
            notification
        )

        Log.d(TAG, "✅ Notification shown for song: ${sentSong.songTitle}")
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.song_listened_channel_name)
            val descriptionText = context.getString(R.string.song_listened_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
