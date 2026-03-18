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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Bug condition exploration test for race condition in PlaybackProgressTracker.
 *
 * Bug: When trackProgress() launches async checkAndStartTracking() and 50% milestone
 * is reached before async completes, handle50PercentMilestone() returns early because
 * currentSentSongId is still null, skipping the Firestore update.
 *
 * Expected: Milestone handler should update Firestore regardless of async timing.
 * Test Result: PASSES on fixed code (confirms bug is fixed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackProgressTrackerRaceConditionTest {

    private lateinit var tracker: PlaybackProgressTracker
    private lateinit var mockRepository: SongSharingRepository
    private lateinit var mockDatabase: MusicDatabase
    private lateinit var mockPlayer: Player

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testSongId = "testSong123"
    private val testSentSongId = "sentSong456"
    private val testPlaylistId = PlaylistEntity.TO_LISTEN_PLAYLIST_ID

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

    @Test
    fun `race condition - 50 percent milestone skipped when async check not complete`() = runTest {
        coEvery { mockDatabase.isSongInPlaylistSync(testPlaylistId, testSongId) } returns 1
        coEvery { mockRepository.getSentSongBySongId(testSongId) } returns SentSong(
            id = testSentSongId, songId = testSongId, songTitle = "Test", songArtist = "Artist",
            songDuration = 100, thumbnailUrl = null, albumId = null, albumName = null,
            fromUid = "sender", fromUsername = "Sender", toUid = "recipient",
            sentAt = 0, listenedAt = null, completedAt = null, notificationSent = false
        )
        coEvery { mockRepository.markSongAsListened(testSentSongId) } returns Unit

        // Step 1: Trigger async check
        tracker.trackProgress(mockPlayer, testScope, testPlaylistId)
        assertNull("currentSentSongId should be null before async completes", tracker.currentSentSongId)

        // Step 2: Simulate 50% progress BEFORE async completes (race condition)
        every { mockPlayer.currentPosition } returns 50000L
        tracker.trackProgress(mockPlayer, testScope, testPlaylistId)

        // Step 3: Complete async check
        advanceUntilIdle()

        // FIX VERIFICATION: On fixed code, markSongAsListened SHOULD be called
        // because handle50PercentMilestone() now retries when currentSentSongId is null
        coVerify(exactly = 1) { mockRepository.markSongAsListened(testSentSongId) }
    }
}