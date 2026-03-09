package com.dd3boh.outertune.social

import java.io.Serializable

/**
 * Data models for "Send to Friend" feature
 */

/**
 * Represents a song sent to a friend via Firebase
 */
data class SentSong(
    val id: String = "", // Firebase document ID
    val songId: String = "", // YouTube/Local song ID
    val songTitle: String = "",
    val songArtist: String = "",
    val songDuration: Int = 0, // in seconds
    val thumbnailUrl: String? = null,
    val fromUid: String = "", // Sender's Firebase UID
    val fromUsername: String = "", // Sender's username for display
    val toUid: String = "", // Recipient's Firebase UID
    val sentAt: Long = System.currentTimeMillis(),
    val listenedAt: Long? = null, // Timestamp when 50% milestone reached
    val completedAt: Long? = null, // Timestamp when song finished playing
    val notificationSent: Boolean = false // Whether sender was notified of 50% milestone
) : Serializable {
    /**
     * Convert to map for Firebase
     */
    fun toMap(): Map<String, Any?> = mapOf(
        "songId" to songId,
        "songTitle" to songTitle,
        "songArtist" to songArtist,
        "songDuration" to songDuration,
        "thumbnailUrl" to thumbnailUrl,
        "fromUid" to fromUid,
        "fromUsername" to fromUsername,
        "toUid" to toUid,
        "sentAt" to sentAt,
        "listenedAt" to listenedAt,
        "completedAt" to completedAt,
        "notificationSent" to notificationSent
    )

    companion object {
        /**
         * Create from Firebase document
         */
        fun fromMap(id: String, map: Map<String, Any?>): SentSong {
            return SentSong(
                id = id,
                songId = map["songId"] as? String ?: "",
                songTitle = map["songTitle"] as? String ?: "",
                songArtist = map["songArtist"] as? String ?: "",
                songDuration = (map["songDuration"] as? Long)?.toInt() ?: 0,
                thumbnailUrl = map["thumbnailUrl"] as? String,
                fromUid = map["fromUid"] as? String ?: "",
                fromUsername = map["fromUsername"] as? String ?: "",
                toUid = map["toUid"] as? String ?: "",
                sentAt = map["sentAt"] as? Long ?: System.currentTimeMillis(),
                listenedAt = map["listenedAt"] as? Long,
                completedAt = map["completedAt"] as? Long,
                notificationSent = map["notificationSent"] as? Boolean ?: false
            )
        }
    }
}

/**
 * Represents a friend selected for sending songs
 */
data class FriendSelection(
    val uid: String,
    val username: String,
    val photoUrl: String?,
    val isSelected: Boolean = false
)
