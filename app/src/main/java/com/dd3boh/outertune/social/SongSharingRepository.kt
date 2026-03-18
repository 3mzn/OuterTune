package com.dd3boh.outertune.social

import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.viewmodels.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongSharingRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val database: MusicDatabase
) {
    private val TAG = "SongSharingRepository"
    private val sentSongsCollection get() = firestore.collection("sentSongs")

    /**
     * Initialize "To Listen" playlist if it doesn't exist
     */
    suspend fun initializeToListenPlaylist() {
        val existingPlaylist = database.playlist(PlaylistEntity.TO_LISTEN_PLAYLIST_ID).firstOrNull()
        
        if (existingPlaylist == null) {
            Log.d(TAG, "Creating 'To Listen' playlist")
            val toListenPlaylist = PlaylistEntity(
                id = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                name = "To Listen",
                browseId = null,
                isEditable = false, // Users cannot manually edit this playlist
                bookmarkedAt = LocalDateTime.now(),
                isLocal = true
            )
            database.query {
                insert(toListenPlaylist)
            }
        }
    }

    /**
     * Send songs to multiple friends
     * @param songs List of songs to send
     * @param friendUids List of friend UIDs to send to
     * @param friendProfiles Map of UID to UserProfile for username lookup
     * @return Number of songs successfully sent
     */
    suspend fun sendSongsToFriends(
        songs: List<MediaMetadata>,
        friendUids: List<String>,
        friendProfiles: Map<String, UserProfile>
    ): Int {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.e(TAG, "❌ Cannot send songs: User not logged in")
            throw Exception("User not logged in")
        }
        
        Log.d(TAG, "✅ User logged in: ${currentUser.uid}")
        
        // Test Firestore connectivity
        try {
            Log.d(TAG, "🔍 Testing Firestore connectivity...")
            val testDoc = firestore.collection("sentSongs").document("test").get().await()
            Log.d(TAG, "✅ Firestore connection successful")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firestore connection failed: ${e.message}")
            throw Exception("Firestore connection failed: ${e.message}")
        }
        
        val currentUsername = getCurrentUsername() ?: "Unknown"
        Log.d(TAG, "📤 Sending ${songs.size} songs from $currentUsername (${currentUser.uid}) to ${friendUids.size} friends")
        
        var successCount = 0
        
        songs.forEach { song ->
            friendUids.forEach { friendUid ->
                try {
                    Log.d(TAG, "🎵 Preparing to send song: ${song.title} to $friendUid")
                    
                    val sentSong = SentSong(
                        songId = song.id,
                        songTitle = song.title,
                        songArtist = song.artists.joinToString(", ") { it.name },
                        songDuration = song.duration,
                        thumbnailUrl = song.thumbnailUrl,
                        albumId = song.album?.id,
                        albumName = song.album?.title,
                        fromUid = currentUser.uid,
                        fromUsername = currentUsername,
                        toUid = friendUid,
                        sentAt = System.currentTimeMillis()
                    )
                    
                    Log.d(TAG, "📝 Writing to Firestore: ${sentSong.toMap()}")
                    val docRef = sentSongsCollection.add(sentSong.toMap()).await()
                    Log.d(TAG, "✅ Successfully wrote document: ${docRef.id}")
                    
                    successCount++
                    Log.d(TAG, "✅ Sent song ${song.title} to $friendUid")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send song ${song.title} to $friendUid")
                    Log.e(TAG, "❌ Exception: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                    // Remove re-throw so the loop can continue to send other songs
                }
            }
        }
        
        Log.d(TAG, "✅ Finished sending. Success count: $successCount")
        return successCount
    }

    /**
     * Listen for incoming songs for current user
     */
    fun observeIncomingSongs(): Flow<List<SentSong>> = callbackFlow {
        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Log.e(TAG, "❌ Cannot observe incoming songs: User not logged in")
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        Log.d(TAG, "👂 Setting up Firestore listener for user: $currentUid")
        
        // Simplified query to avoid needing a composite index
        // We'll filter completedAt and sort by sentAt on the client side
        val registration = sentSongsCollection
            .whereEqualTo("toUid", currentUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error observing incoming songs", error)
                    return@addSnapshotListener
                }
                
                if (snapshot == null) {
                    Log.w(TAG, "⚠️ Snapshot is null")
                    return@addSnapshotListener
                }
                
                Log.d(TAG, "📦 Firestore snapshot received: ${snapshot.documents.size} documents")
                
                // Filter and Sort on the client side
                val songs = snapshot.documents.mapNotNull { doc ->
                    try {
                        val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                        // Only include songs that haven't been completed
                        if (song.completedAt == null) {
                            song
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing sent song from doc ${doc.id}", e)
                        null
                    }
                }.sortedByDescending { it.sentAt } // Client-side sort: newest first
                
                Log.d(TAG, "📬 Sending ${songs.size} songs to Flow")
                trySend(songs)
            }

        Log.d(TAG, "✅ Firestore listener registered")
        awaitClose { 
            Log.d(TAG, "🔌 Removing Firestore listener")
            registration.remove() 
        }
    }

    /**
     * Add incoming song to "To Listen" playlist
     * @param sentSong The song to add
     * @param metadata The MediaMetadata for the song
     * @return AddSongResult indicating SUCCESS, DUPLICATE, or ERROR
     */
    suspend fun addSongToToListenPlaylist(sentSong: SentSong, metadata: MediaMetadata): AddSongResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📥 Starting to add song to To Listen playlist: ${sentSong.songTitle}")
                
                // CRITICAL: Use runTransaction to wait for completion and ensure atomicity
                database.runTransaction {
                    // 1. Double check for duplicates inside the transaction
                    val isDuplicate = isSongInPlaylistSync(
                        PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                        sentSong.songId
                    ) > 0
                    
                    if (isDuplicate) {
                        Log.d(TAG, "⏭️ [Atomic] Song ${sentSong.songTitle} already in To Listen playlist, skipping")
                        return@runTransaction AddSongResult.DUPLICATE
                    }

                    // 2. Check and insert song into library
                    val existing = songSync(sentSong.songId)
                    if (existing == null) {
                        Log.d(TAG, "➕ [Atomic] Inserting song ${sentSong.songId} into library")
                        insert(metadata.toSongEntity())
                    }
                    
                    // 3. Get latest playlist state and shift positions
                    val currentSongs = playlistSongsSync(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                    Log.d(TAG, "🔄 [Atomic] Shifting ${currentSongs.size} songs for index 0")
                    
                    currentSongs.forEach { playlistSong ->
                        update(playlistSong.map.copy(position = playlistSong.map.position + 1))
                    }
                    
                    // 4. Insert at position 0
                    insert(
                        PlaylistSongMap(
                            songId = sentSong.songId,
                            playlistId = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                            position = 0
                        )
                    )
                    
                    Log.d(TAG, "✅ [Atomic] Added ${sentSong.songTitle} at position 0")
                    AddSongResult.SUCCESS
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding song to To Listen playlist: ${e.message}", e)
                e.printStackTrace()
                AddSongResult.ERROR
            }
        }
    }

    /**
     * Mark song as listened (50% milestone reached)
     * @param sentSongId Firebase document ID
     */
    suspend fun markSongAsListened(sentSongId: String) {
        withContext(Dispatchers.IO) {
            try {
                sentSongsCollection.document(sentSongId).update(
                    mapOf(
                        "listenedAt" to System.currentTimeMillis()
                    )
                ).await()
                Log.d(TAG, "✅ Marked song $sentSongId as listened")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error marking song as listened: ${e.message}", e)
                throw e  // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Mark song as completed and remove from "To Listen" playlist
     * @param sentSongId Firebase document ID
     * @param songId YouTube/Local song ID
     */
    suspend fun markSongAsCompleted(sentSongId: String, songId: String) {
        withContext(Dispatchers.IO) {
            try {
                // Perform local removal first
                database.transaction {
                    val playlistSongs = playlistSongsSync(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                    val songToRemove = playlistSongs.find { it.song.id == songId }
                    if (songToRemove != null) {
                        val removedPosition = songToRemove.map.position
                        delete(songToRemove.map)
                        
                        // Shift positions
                        playlistSongs
                            .filter { it.map.position > removedPosition }
                            .forEach { playlistSong ->
                                update(playlistSong.map.copy(position = playlistSong.map.position - 1))
                            }
                        Log.d(TAG, "✅ Local: Removed song $songId from To Listen playlist")
                    }
                }

                // Update Firestore
                Log.d(TAG, "☁️ Cloud: Updating Firebase for $sentSongId (completedAt)")
                sentSongsCollection.document(sentSongId).update(
                    mapOf(
                        "completedAt" to System.currentTimeMillis()
                    )
                ).await()
                Log.d(TAG, "✅ Cloud: Marked song $sentSongId as completed")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error marking song as completed: ${e.message}", e)
                throw e  // Re-throw so caller knows it failed
            }
        }
    }

    /**
     * Clear all songs from "To Listen" playlist and mark them as completed in Firestore
     */
    suspend fun clearToListenPlaylist() {
        val currentUid = auth.currentUser?.uid ?: return
        
        try {
            Log.d(TAG, "🧹 Clearing To Listen playlist for user: $currentUid")
            
            // 1. Get all pending documents from Firestore for this user
            val snapshot = sentSongsCollection
                .whereEqualTo("toUid", currentUid)
                .get()
                .await()
                
            val pendingDocs = snapshot.documents.filter { doc ->
                doc.get("completedAt") == null
            }
            
            Log.d(TAG, "🔍 Found ${pendingDocs.size} pending songs in Firestore to mark as completed")
            
            // 2. Mark them as completed in Firestore using a batch
            if (pendingDocs.isNotEmpty()) {
                val batch = firestore.batch()
                val now = System.currentTimeMillis()
                pendingDocs.forEach { doc ->
                    batch.update(doc.reference, "completedAt", now)
                }
                batch.commit().await()
                Log.d(TAG, "✅ Marked ${pendingDocs.size} songs as completed in Firestore")
            }
            
            // 3. Clear local database entries for this playlist
            database.transaction {
                clearPlaylist(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
            }
            Log.d(TAG, "✅ Local To Listen playlist cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing To Listen playlist: ${e.message}", e)
            throw e // Re-throw to show error in UI
        }
    }
    /**
     * Mark notification as sent for a song
     * @param sentSongId Firebase document ID
     */
    suspend fun markNotificationSent(sentSongId: String) {
        try {
            sentSongsCollection.document(sentSongId).update(
                mapOf(
                    "notificationSent" to true
                )
            ).await()
            Log.d(TAG, "Marked notification as sent for $sentSongId")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking notification as sent: ${e.message}", e)
            // Don't throw - notification was shown locally
            // This prevents duplicate notifications on retry
        }
    }

    /**
     * Get songs that have been listened to but sender hasn't been notified yet
     * Used by background worker and real-time listener
     * @param fromUid The sender's UID (current user)
     * @param since Only get songs sent after this timestamp (30 days ago)
     * @return List of SentSong that need notification
     */
    suspend fun getListenedSongsNeedingNotification(fromUid: String, since: Long): List<SentSong> {
        return try {
            val snapshot = sentSongsCollection
                .whereEqualTo("fromUid", fromUid)
                .whereGreaterThan("sentAt", since)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                try {
                    val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                    // Only include songs that have been listened to but not notified
                    if (song.listenedAt != null && !song.notificationSent) {
                        song
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing sent song from doc ${doc.id}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting listened songs needing notification", e)
            emptyList()
        }
    }

    /**
     * Observe songs that have been listened to but sender hasn't been notified yet
     * Used for real-time notifications when app is open
     * @param fromUid The sender's UID (current user)
     * @param since Only get songs sent after this timestamp (30 days ago)
     * @return Flow of SentSong list that need notification
     */
    fun observeListenedSongsNeedingNotification(fromUid: String, since: Long): Flow<List<SentSong>> = callbackFlow {
        Log.d(TAG, "👂 Setting up real-time listener for listened songs notifications")

        val registration = sentSongsCollection
            .whereEqualTo("fromUid", fromUid)
            .whereGreaterThan("sentAt", since)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "❌ Error observing listened songs", error)
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    Log.w(TAG, "⚠️ Snapshot is null")
                    return@addSnapshotListener
                }

                Log.d(TAG, "📦 Received ${snapshot.documents.size} documents for notification check")

                val songs = snapshot.documents.mapNotNull { doc ->
                    try {
                        val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                        // Only include songs that have been listened to but not notified
                        if (song.listenedAt != null && !song.notificationSent) {
                            Log.d(TAG, "🔔 Song needs notification: ${song.songTitle}")
                            song
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing sent song from doc ${doc.id}", e)
                        null
                    }
                }

                trySend(songs)
            }

        awaitClose {
            Log.d(TAG, "🔌 Removing real-time listener for notifications")
            registration.remove()
        }
    }

    /**
     * Get sent song by song ID for current user
     * @param songId YouTube/Local song ID
     * @return SentSong if found, null otherwise
     */
    suspend fun getSentSongBySongId(songId: String): SentSong? {
        val currentUid = auth.currentUser?.uid ?: return null
        
        Log.i(TAG, "🔍 [Repository] Searching for sent song $songId for recipient $currentUid")
        
        return try {
            val snapshot = sentSongsCollection
                .whereEqualTo("toUid", currentUid)
                .whereEqualTo("songId", songId)
                .get()
                .await()
            
            Log.i(TAG, "📦 [Repository] Found ${snapshot.documents.size} documents for song $songId (User: $currentUid)")
            
            if (snapshot.isEmpty) {
                // Diagnostic: Log all songs for this user to help find the mismatch
                val allUserSongs = sentSongsCollection.whereEqualTo("toUid", currentUid).get().await()
                Log.i(TAG, "📋 [Diagnostic] User $currentUid has ${allUserSongs.size()} total songs in Firestore.")
                allUserSongs.documents.forEach { doc ->
                    Log.i(TAG, "   - docId: ${doc.id}, songId: '${doc.get("songId")}', toUid: '${doc.get("toUid")}', completedAt: ${doc.get("completedAt")}")
                }
            }

            snapshot.documents
                .mapNotNull { doc ->
                    try {
                        SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing sent song from doc ${doc.id}", e)
                        null
                    }
                }
                .filter { it.completedAt == null }
                .sortedByDescending { it.sentAt }
                .firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sent song by ID", e)
            null
        }
    }

    /**
     * Get current user's username from Firestore
     */
    private suspend fun getCurrentUsername(): String? {
        val currentUid = auth.currentUser?.uid ?: return null
        
        return try {
            val doc = firestore.collection("users").document(currentUid).get().await()
            doc.getString("username")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current username", e)
            null
        }
    }
}
