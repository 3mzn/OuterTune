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
                    throw e // Re-throw to show in toast
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
        // We'll filter completedAt on the client side
        val registration = sentSongsCollection
            .whereEqualTo("toUid", currentUid)
            .orderBy("sentAt", Query.Direction.DESCENDING)
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
                
                // Filter out completed songs on the client side
                val songs = snapshot.documents.mapNotNull { doc ->
                    try {
                        val song = SentSong.fromMap(doc.id, doc.data ?: emptyMap())
                        // Only include songs that haven't been completed
                        if (song.completedAt == null) {
                            Log.d(TAG, "✅ Parsed song: ${song.songTitle} from ${song.fromUsername}")
                            song
                        } else {
                            Log.d(TAG, "⏭️ Skipping completed song: ${song.songTitle}")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error parsing sent song from doc ${doc.id}", e)
                        null
                    }
                }
                
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
     * @return true if added successfully, false if duplicate or error
     */
    suspend fun addSongToToListenPlaylist(sentSong: SentSong, metadata: MediaMetadata): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📥 Starting to add song to To Listen playlist: ${sentSong.songTitle}")
                
                // Check for duplicates
                val isDuplicate = database.isSongInPlaylist(
                    PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                    sentSong.songId
                ) > 0
                
                if (isDuplicate) {
                    Log.d(TAG, "⏭️ Song ${sentSong.songTitle} already in To Listen playlist, skipping")
                    return@withContext false
                }

                Log.d(TAG, "🔍 Checking if song exists in library: ${sentSong.songId}")
                // Check if song exists in library
                val existing = database.song(sentSong.songId).firstOrNull()
                if (existing != null) {
                    Log.d(TAG, "✅ Song already exists in library")
                } else {
                    Log.d(TAG, "❌ Song not in library, will insert")
                }
                
                // Get existing songs in playlist
                Log.d(TAG, "📋 Fetching existing songs in To Listen playlist...")
                val existingSongs = database.playlistSongs(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                    .firstOrNull() ?: emptyList()
                Log.d(TAG, "📊 Found ${existingSongs.size} existing songs in playlist")

                // Insert song and update playlist
                Log.d(TAG, "💾 Starting database transaction...")
                database.query {
                    // Insert song into library if not exists
                    if (existing == null) {
                        Log.d(TAG, "➕ Inserting song into library")
                        insert(metadata.toSongEntity())
                    }

                    // Shift all existing songs down by 1
                    Log.d(TAG, "🔄 Shifting ${existingSongs.size} existing songs down by 1 position")
                    existingSongs.forEach { playlistSong ->
                        update(playlistSong.map.copy(position = playlistSong.map.position + 1))
                    }
                    
                    // Insert new song at position 0
                    Log.d(TAG, "➕ Inserting new song at position 0")
                    insert(
                        PlaylistSongMap(
                            songId = sentSong.songId,
                            playlistId = PlaylistEntity.TO_LISTEN_PLAYLIST_ID,
                            position = 0
                        )
                    )
                }

                Log.d(TAG, "✅ Successfully added song ${sentSong.songTitle} to To Listen playlist")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error adding song to To Listen playlist: ${e.message}", e)
                e.printStackTrace()
                return@withContext false
            }
        }
    }

    /**
     * Mark song as listened (50% milestone reached)
     * @param sentSongId Firebase document ID
     */
    suspend fun markSongAsListened(sentSongId: String) {
        try {
            sentSongsCollection.document(sentSongId).update(
                mapOf(
                    "listenedAt" to System.currentTimeMillis()
                )
            ).await()
            Log.d(TAG, "Marked song $sentSongId as listened")
            
            // TODO: Send FCM notification to sender
            // This requires Firebase Cloud Functions or a backend server
            // For now, the notification will be triggered by Firestore triggers
            // on the backend (not implemented in this client-side code)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking song as listened", e)
        }
    }

    /**
     * Mark song as completed and remove from "To Listen" playlist
     * @param sentSongId Firebase document ID
     * @param songId YouTube/Local song ID
     */
    suspend fun markSongAsCompleted(sentSongId: String, songId: String) {
        try {
            // Update Firebase
            sentSongsCollection.document(sentSongId).update(
                mapOf(
                    "completedAt" to System.currentTimeMillis()
                )
            ).await()

            // Remove from "To Listen" playlist
            val playlistSongs = database.playlistSongs(PlaylistEntity.TO_LISTEN_PLAYLIST_ID)
                .firstOrNull() ?: emptyList()
            
            val songToRemove = playlistSongs.find { it.song.id == songId }
            if (songToRemove != null) {
                database.query {
                    delete(songToRemove.map)
                }
                Log.d(TAG, "Removed song $songId from To Listen playlist")
            }

            Log.d(TAG, "Marked song $sentSongId as completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error marking song as completed", e)
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
            Log.e(TAG, "Error marking notification as sent", e)
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
        
        return try {
            val snapshot = sentSongsCollection
                .whereEqualTo("toUid", currentUid)
                .whereEqualTo("songId", songId)
                .whereEqualTo("completedAt", null)
                .limit(1)
                .get()
                .await()
            
            snapshot.documents.firstOrNull()?.let { doc ->
                SentSong.fromMap(doc.id, doc.data ?: emptyMap())
            }
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
