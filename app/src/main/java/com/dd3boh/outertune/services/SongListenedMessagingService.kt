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
        // Notification data keys
        const val KEY_TYPE = "type"
        const val KEY_FRIEND_NAME = "friendName"
        const val KEY_SONG_TITLE = "songTitle"
        const val KEY_SENT_SONG_ID = "sentSongId"
        
        const val TYPE_SONG_LISTENED = "song_listened"
    }

    override fun onCreate() {
        super.onCreate()
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
        com.dd3boh.outertune.utils.SongNotificationHelper.showNotification(
            this, 
            friendName, 
            songTitle
        )
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // TODO: Send token to your server if needed
    }
}
