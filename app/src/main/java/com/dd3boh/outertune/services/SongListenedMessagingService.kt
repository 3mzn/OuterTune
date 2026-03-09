package com.dd3boh.outertune.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging service for handling "song listened" notifications
 */
class SongListenedMessagingService : FirebaseMessagingService() {
    private val TAG = "SongListenedMessaging"

    companion object {
        const val CHANNEL_ID = "song_listened_notifications"
        const val NOTIFICATION_ID_BASE = 2000
        
        // Notification data keys
        const val KEY_TYPE = "type"
        const val KEY_FRIEND_NAME = "friendName"
        const val KEY_SONG_TITLE = "songTitle"
        const val KEY_SENT_SONG_ID = "sentSongId"
        
        const val TYPE_SONG_LISTENED = "song_listened"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        Log.d(TAG, "Message received from: ${message.from}")
        
        val data = message.data
        val type = data[KEY_TYPE]
        
        when (type) {
            TYPE_SONG_LISTENED -> handleSongListenedNotification(data)
            else -> Log.w(TAG, "Unknown notification type: $type")
        }
    }

    /**
     * Handle "song listened" notification
     */
    private fun handleSongListenedNotification(data: Map<String, String>) {
        val friendName = data[KEY_FRIEND_NAME] ?: "A friend"
        val songTitle = data[KEY_SONG_TITLE] ?: "your song"
        
        Log.d(TAG, "Song listened notification: $friendName listened to $songTitle")
        
        // Show notification
        showNotification(friendName, songTitle)
    }

    /**
     * Show notification to user
     */
    private fun showNotification(friendName: String, songTitle: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create intent to open Social screen
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "social") // MainActivity will handle navigation
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.music_note)
            .setContentTitle(getString(R.string.friend_listened_to_song))
            .setContentText(getString(R.string.friend_listened_to_song_message, friendName, songTitle))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.friend_listened_to_song_message, friendName, songTitle))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        // Show notification with unique ID based on timestamp
        notificationManager.notify(
            NOTIFICATION_ID_BASE + System.currentTimeMillis().toInt(),
            notification
        )
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.song_listened_notifications)
            val descriptionText = getString(R.string.song_listened_notifications_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // TODO: Send token to your server if needed
    }
}
