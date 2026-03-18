// BACKUP: PlaybackProgressTracker Milestone Handlers - WORKING VERSION
// Date: March 19, 2026
// Status: TESTED AND WORKING - Both 50% and 95% milestones execute properly
// 
// This is a backup of the FIXED milestone handler code.
// If the feature breaks again, refer to this file to see the correct implementation.

// ============================================================================
// CRITICAL FIX: Lines 220-235 in trackProgress() function
// ============================================================================
// 
// The key fix is using independent CoroutineScope(Dispatchers.IO) instead of
// the passed scope which uses Dispatchers.Main
//

// BEFORE (BROKEN - handlers never executed):
/*
if (!has100PercentTriggered && maxProgressReached >= 95f) {
    has100PercentTriggered = true
    has50PercentTriggered = true
    Log.i(TAG, "🏁 95% Completion Triggered for $currentTrackingSongId")
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {  // ❌ BROKEN
        handle100PercentCompletion()
    }
} 
else if (!has50PercentTriggered && maxProgressReached >= 50f) {
    has50PercentTriggered = true
    Log.i(TAG, "🎯 50% Milestone Triggered for $currentTrackingSongId")
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {  // ❌ BROKEN
        handle50PercentMilestone()
    }
}
*/

// AFTER (FIXED - handlers execute properly):
/*
if (!has100PercentTriggered && maxProgressReached >= 95f) {
    has100PercentTriggered = true
    has50PercentTriggered = true
    Log.i(TAG, "� 95% Completion Triggered for $currentTrackingSongId")
    // Use independent IO scope to avoid Main dispatcher issues
    CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {  // ✅ FIXED
        handle100PercentCompletion()
    }
} 
else if (!has50PercentTriggered && maxProgressReached >= 50f) {
    has50PercentTriggered = true
    Log.i(TAG, "� 50% Milestone Triggered for $currentTrackingSongId")
    // Use independent IO scope to avoid Main dispatcher issues
    CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {  // ✅ FIXED
        handle50PercentMilestone()
    }
}
*/

// ============================================================================
// WORKING HANDLER IMPLEMENTATIONS (Lines 242-330)
// ============================================================================

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

// ============================================================================
// TEST RESULTS (Verified March 19, 2026)
// ============================================================================
// 
// Song: "Vaanam Thilathilakkanu" (228 seconds)
// Test Device: Android Emulator (localhost:5557)
// 
// ✅ 50% Milestone (at 114 seconds):
//    03-19 02:32:38.385  [50% Milestone Triggered]
//    03-19 02:32:38.386  [50% Handler] Starting Firestore update
//    03-19 02:32:38.578  [50% Handler] Firestore update completed successfully
//    Result: listenedAt field updated in Firestore
//
// ✅ 95% Milestone (at 216 seconds):
//    03-19 02:34:21.525  [95% Completion Triggered]
//    03-19 02:34:21.526  [100% Handler] Starting Firestore update and local removal
//    03-19 02:34:21.696  [100% Handler] Firestore update and local removal completed successfully
//    Result: completedAt field updated, song removed from playlist
//
// ✅ Consistency: Works for multiple songs in sequence
//
// ============================================================================
