package com.dd3boh.outertune.social

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the background worker for song listened notifications
 */
@Singleton
class SongListenedNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth
) {
    private val TAG = "SongListenedNotifMgr"
    private val workManager = WorkManager.getInstance(context)

    /**
     * Start the background worker
     * Called when user logs in
     */
    fun startWorker() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "User not logged in, not starting worker")
            return
        }

        Log.d(TAG, "Starting background notification worker")

        // Create constraints - only run when connected to internet
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Create periodic work request - runs every 15 minutes
        val workRequest = PeriodicWorkRequestBuilder<SongListenedNotificationWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // Enqueue work - replace existing if already scheduled
        workManager.enqueueUniquePeriodicWork(
            SongListenedNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already running
            workRequest
        )

        Log.d(TAG, "Background worker scheduled")
    }

    /**
     * Stop the background worker
     * Called when user logs out
     */
    fun stopWorker() {
        Log.d(TAG, "Stopping background notification worker")
        workManager.cancelUniqueWork(SongListenedNotificationWorker.WORK_NAME)
    }

    /**
     * Check if worker is running
     */
    fun isWorkerRunning(): Boolean {
        val workInfos = workManager.getWorkInfosForUniqueWork(
            SongListenedNotificationWorker.WORK_NAME
        ).get()
        return workInfos.any { !it.state.isFinished }
    }

    /**
     * Manually trigger the worker to run immediately (for testing)
     */
    fun triggerWorkerNow() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.d(TAG, "User not logged in, cannot trigger worker")
            return
        }

        Log.d(TAG, "Manually triggering notification worker")

        // Create one-time work request
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<SongListenedNotificationWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueue(workRequest)
        Log.d(TAG, "One-time worker triggered")
    }
}
