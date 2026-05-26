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

            // Only notify for songs listened to in the last 24 hours
            val oneDayAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            val recentSongs = listenedSongs.filter { song ->
                song.listenedAt != null && song.listenedAt > oneDayAgo
            }
            
            Log.d(TAG, "🔔 Filtering to ${recentSongs.size} recent songs (last 24h)")

            // Show notification for each recent song
            recentSongs.forEach { sentSong ->
                com.dd3boh.outertune.utils.SongNotificationHelper.showNotification(context, sentSong)
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
}
