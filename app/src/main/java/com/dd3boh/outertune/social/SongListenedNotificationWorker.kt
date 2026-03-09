package com.dd3boh.outertune.social

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.google.firebase.auth.FirebaseAuth
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that checks for songs listened by friends
 * Runs every 15 minutes when app is closed
 */
@HiltWorker
class SongListenedNotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val songSharingRepository: SongSharingRepository,
    private val auth: FirebaseAuth
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "SongListenedWorker"
        const val CHANNEL_ID = "song_listened_notifications"
        const val NOTIFICATION_ID_BASE = 3000
        const val WORK_NAME = "song_listened_notification_worker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker started - checking for listened songs")

        // Check if user is logged in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "User not logged in, skipping check")
            return Result.success()
        }

        return try {
            // Get songs that have been listened to but not notified
            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            val listenedSongs = songSharingRepository.getListenedSongsNeedingNotification(
                fromUid = currentUser.uid,
                since = thirtyDaysAgo
            )

            Log.d(TAG, "Found ${listenedSongs.size} songs needing notification")

            // Show notification for each song
            listenedSongs.forEach { sentSong ->
                showNotification(sentSong)
                // Mark as notified
                songSharingRepository.markNotificationSent(sentSong.id)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for listened songs", e)
            // Retry on failure
            Result.retry()
        }
    }

    private fun showNotification(sentSong: SentSong) {
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

        Log.d(TAG, "Notification shown for song: ${sentSong.songTitle}")
    }

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
}
