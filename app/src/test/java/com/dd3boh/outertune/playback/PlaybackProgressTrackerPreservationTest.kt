package com.dd3boh.outertune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.social.SentSong
import com.dd3boh.outertune.social.SongSharingRepository
import com.google.firebase.auth.FirebaseAuth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Preservation property tests for PlaybackProgressTracker.
 *
 * These tests verify baseline behavior that must be preserved after any fix:
 * 1. Songs NOT from "To Listen" playlist - no tracking occurs
 * 2. Songs from "To Listen" playlist with valid SentSong - tracking works correctly
 * 3. Blacklist prevents redundant Firestore queries for failed songs
 *
 * Expected: Tests PASS on unfixed code (confirms baseline behavior to preserve).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressTrackerPreservationTest {

    private lateinit var tracker: PlaybackProgressTracker
    private lateinit var mockRepository: SongSharingRepository
    private lateinit var mockDatabase: MusicDatabase
    private lateinit var mockPlayer: Player

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testSongId = "testSong123"
    private val testSentSongId = "sentSong456"
    private val toListenPlaylistId = PlaylistEntity.TO_LISTEN_PLAYLIST_ID
    private val otherPlaylistId = "other_playlist_789"

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(FirebaseAuth::class)
        val mockAuth = mockk<FirebaseAuth>(relaxed = true)
        every { FirebaseAuth.getInstance() } returns mockAuth
        mockRepository = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)
        tracker = PlaybackProgressTracker(mockRepository, mockDatabase)
        mockPlayer = mockk(relaxed = true)

        every { mockPlayer.currentMediaItem } returns MediaItem.Builder()
            .setMediaId(testSongId)
            .build()
        every { mockPlayer.currentPosition } returns 0L
        every { mockPlayer.duration } returns 100000L
        every { mockPlayer.isPlaying } returns true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ============================================
    // PRESERVATION PROPERTY 2.1: Non-To-Listen Playlist
    // Songs NOT from "To Listen" playlist should NOT be tracked
    // ============================================

    @Test
    fun `preservation - songs from other playlist are not tracked`() = runTest {
        // Given: A song from a different playlist (not "To Listen")
        val otherPlaylistId = "user_playlist_123"

        // When: trackProgress is called with non-To-Listen playlist
        tracker.trackProgress(mockPlayer, testScope, otherPlaylistId)
        advanceUntilIdle()

        // Then: No tracking state should be set
        assertNull("currentTrackingSongId should be null for non-To-Listen playlist", tracker.currentTrackingSongId)
        assertNull("currentSentSongId should be null for non-To-Listen playlist", tracker.currentSentSongId)
    }

    @Test
    fun `preservation - songs from null playlist are not tracked`() = runTest {
        // Given: No playlist context (null)
        // When: trackProgress is called with null playlist
        tracker.trackProgress(mockPlayer, testScope, null)
        advanceUntilIdle()

        // Then: No tracking state should be set
        assertNull("currentTrackingSongId should be null for null playlist", tracker.currentTrackingSongId)
        assertNull("currentSentSongId should be null for null playlist", tracker.currentSentSongId)
    }

    @Test
    fun `preservation - onMediaItemTransition with non-To-Listen playlist does not start tracking`() = runTest {
        // Given: A media item and non-To-Listen playlist
        val mediaItem = MediaItem.Builder().setMediaId(testSongId).build()
        val otherPlaylistId = "regular_playlist"

        // When: onMediaItemTransition is called with non-To-Listen playlist
        tracker.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, otherPlaylistId)
        advanceUntilIdle()

        // Then: No tracking state should be set
        assertNull("currentTrackingSongId should be null", tracker.currentTrackingSongId)
        assertNull("currentSentSongId should be null", tracker.currentSentSongId)
    }

    @Test
    fun `preservation - no Firestore operations for non-To-Listen playlist songs`() = runTest {
        // Given: A song from a different playlist
        val otherPlaylistId = "another_playlist"

        // When: trackProgress is called multiple times
        tracker.trackProgress(mockPlayer, testScope, otherPlaylistId)
        tracker.trackProgress(mockPlayer, testScope, otherPlaylistId)
        tracker.trackProgress(mockPlayer, testScope, otherPlaylistId)
        advanceUntilIdle()

        // Then: No Firestore operations should be called
        coVerify(exactly = 0) { mockRepository.getSentSongBySongId(any()) }
        coVerify(exactly = 0) { mockRepository.markSongAsListened(any()) }
        coVerify(exactly = 0) { mockRepository.markSongAsCompleted(any(), any()) }
    }

    // ============================================
    // PRESERVATION PROPERTY 2.2: Valid SentSong Tracking
    // Songs from "To Listen" playlist with valid SentSong should track correctly
    // ============================================

    @Test
    fun `preservation - valid SentSong starts tracking correctly`() = runTest {
        // Given: Song is in "To Listen" playlist and has valid SentSong
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns SentSong(
            id = testSentSongId, songId = testSongId, songTitle = "Test Song", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )

        // When: trackProgress is called with To-Listen playlist
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Then: Tracking state should be set
        assertEquals("currentTrackingSongId should be set", testSongId, tracker.currentTrackingSongId)
        assertEquals("currentSentSongId should be set", testSentSongId, tracker.currentSentSongId)
    }

    @Test
    fun `preservation - 50 percent milestone updates Firestore for valid tracking`() = runTest {
        // Given: Valid tracking is active
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns SentSong(
            id = testSentSongId, songId = testSongId, songTitle = "Test Song", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )
        coEvery { mockRepository.markSongAsListened(testSentSongId) } returns Unit

        // Start tracking first
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // When: 50% milestone is reached
        every { mockPlayer.currentPosition } returns 50000L
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Then: markSongAsListened should be called
        coVerify(exactly = 1) { mockRepository.markSongAsListened(testSentSongId) }
    }

    @Test
    fun `preservation - tracking state is reset on new media item transition`() = runTest {
        // Given: Tracking is active for first song
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns SentSong(
            id = testSentSongId, songId = testSongId, songTitle = "Test Song", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )

        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Verify tracking started
        assertEquals("Tracking should have started", testSongId, tracker.currentTrackingSongId)

        // When: New media item transitions
        val newSongId = "newSong456"
        val newMediaItem = MediaItem.Builder().setMediaId(newSongId).build()
        every { mockPlayer.currentMediaItem } returns newMediaItem

        tracker.onMediaItemTransition(newMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, toListenPlaylistId)
        advanceUntilIdle()

        // Then: Previous tracking state should be cleared
        assertNull("currentTrackingSongId should be reset", tracker.currentTrackingSongId)
        assertNull("currentSentSongId should be reset", tracker.currentSentSongId)
    }

    // ============================================
    // PRESERVATION PROPERTY 2.3: Blacklist Behavior
    // Blacklist prevents redundant Firestore queries for failed songs
    // ============================================

    @Test
    fun `preservation - blacklist prevents redundant queries for songs without SentSong`() = runTest {
        // Given: First call finds no SentSong (adds to blacklist)
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns null // No SentSong

        // When: First call - should query Firestore
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // When: Second call - should NOT query Firestore again
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Then: getSentSongBySongId should only be called once (first call)
        coVerify(exactly = 1) { mockRepository.getSentSongBySongId(testSongId) }
    }

    @Test
    fun `preservation - blacklist is cleared on new song transition`() = runTest {
        // Given: First song fails and gets blacklisted
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns null

        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // When: New song transitions
        val newSongId = "newSong789"
        val newMediaItem = MediaItem.Builder().setMediaId(newSongId).build()
        every { mockPlayer.currentMediaItem } returns newMediaItem

        tracker.onMediaItemTransition(newMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, toListenPlaylistId)
        advanceUntilIdle()

        // Then: Blacklist should be cleared (lastFailedSongId reset)
        // The new song should be able to query Firestore
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, newSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(newSongId) } returns SentSong(
            id = "newSentSong", songId = newSongId, songTitle = "New Song", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )

        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Then: New song should be tracked (blacklist was cleared)
        assertEquals("New song should be tracked", newSongId, tracker.currentTrackingSongId)
    }

    @Test
    fun `preservation - songs not in local playlist are blacklisted without Firestore query`() = runTest {
        // Given: Song is NOT in local "To Listen" playlist
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 0

        // When: trackProgress is called
        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Then: No Firestore query should be made (skipped at local DB check)
        coVerify(exactly = 0) { mockRepository.getSentSongBySongId(any()) }
    }

    @Test
    fun `preservation - playback state change resets tracking`() = runTest {
        // Given: Tracking is active
        coEvery { mockDatabase.isSongInPlaylistSync(toListenPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns SentSong(
            id = testSentSongId, songId = testSongId, songTitle = "Test Song", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )

        tracker.trackProgress(mockPlayer, testScope, toListenPlaylistId)
        advanceUntilIdle()

        // Verify tracking started
        assertEquals("Tracking should have started", testSongId, tracker.currentTrackingSongId)

        // When: Playback state changes to IDLE
        tracker.onPlaybackStateChanged(Player.STATE_IDLE)
        advanceUntilIdle()

        // Then: Tracking should be stopped
        assertNull("currentTrackingSongId should be null after IDLE", tracker.currentTrackingSongId)
        assertNull("currentSentSongId should be null after IDLE", tracker.currentSentSongId)
    }
}