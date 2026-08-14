package com.panmusic.carplayer.viewmodel

import app.cash.turbine.test
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.model.FormatInfo
import com.panmusic.carplayer.model.PlayMode
import com.panmusic.carplayer.model.PlaybackState
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.repository.PlaybackRepository
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlayerViewModelTest {
    @get:Rule val mainCoroutineRule = MainCoroutineRule()

    private val playbackRepo = mockk<PlaybackRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val playbackStateFlow = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    private val currentSongFlow = MutableStateFlow<Song?>(null)
    private val progressFlow = MutableStateFlow(0L)
    private val formatInfoFlow = MutableStateFlow(FormatInfo())
    private val playModeFlow = MutableStateFlow(PlayMode.ORDER)

    private fun setupVM(): PlayerViewModel {
        every { playbackRepo.playbackState } returns playbackStateFlow
        every { playbackRepo.currentSong } returns currentSongFlow
        every { playbackRepo.progress } returns progressFlow
        every { playbackRepo.formatInfo } returns formatInfoFlow
        every { settingsRepo.playMode } returns playModeFlow
        return PlayerViewModel(playbackRepo, settingsRepo)
    }

    @Test fun `initial state is idle`() = runTest {
        setupVM().uiState.test { assertEquals(PlaybackState.Idle, awaitItem().playbackState) }
    }

    @Test fun `play delegates to repo`() = runTest {
        every { playbackRepo.play() } just Runs
        setupVM().play()
        verify(exactly = 1) { playbackRepo.play() }
    }

    @Test fun `pause delegates to repo`() = runTest {
        every { playbackRepo.pause() } just Runs
        setupVM().pause()
        verify(exactly = 1) { playbackRepo.pause() }
    }

    @Test fun `playNext delegates to repo`() = runTest {
        every { playbackRepo.playNext() } just Runs
        setupVM().playNext()
        verify(exactly = 1) { playbackRepo.playNext() }
    }

    @Test fun `seekTo delegates to repo`() = runTest {
        every { playbackRepo.seekTo(any()) } just Runs
        setupVM().seekTo(15000L)
        verify { playbackRepo.seekTo(15000L) }
    }

    @Test fun `cyclePlayMode ORDER-RANDOM-SINGLE-ORDER`() = runTest {
        every { settingsRepo.setPlayMode(any()) } just Runs
        playModeFlow.value = PlayMode.ORDER
        val vm = setupVM()
        vm.cyclePlayMode(); verify { settingsRepo.setPlayMode(PlayMode.RANDOM) }
        playModeFlow.value = PlayMode.RANDOM
        vm.cyclePlayMode(); verify { settingsRepo.setPlayMode(PlayMode.SINGLE) }
        playModeFlow.value = PlayMode.SINGLE
        vm.cyclePlayMode(); verify { settingsRepo.setPlayMode(PlayMode.ORDER) }
    }

    @Test fun `Playing state has isPlaying true`() = runTest {
        playbackStateFlow.value = PlaybackState.Playing(5000L, 300000L)
        setupVM().uiState.test { assertTrue(awaitItem().isPlaying) }
    }

    @Test fun `Paused state has isPlaying false`() = runTest {
        playbackStateFlow.value = PlaybackState.Paused(10000L, 300000L)
        setupVM().uiState.test { assertFalse(awaitItem().isPlaying) }
    }

    @Test fun `song title from currentSong`() = runTest {
        currentSongFlow.value = Song(id = 1, fsId = 123L, serverFilename = "test.mp3")
        setupVM().uiState.test { assertEquals("test.mp3", awaitItem().songTitle) }
    }

    @Test fun `format info flows to UI`() = runTest {
        formatInfoFlow.value = FormatInfo(sampleRate = 44100, channelCount = 2, bitrate = 320000)
        setupVM().uiState.test {
            val s = awaitItem()
            assertEquals(44100, s.formatInfo.sampleRate)
            assertEquals(2, s.formatInfo.channelCount)
        }
    }

    @Test fun `playMode flows from settings`() = runTest {
        playModeFlow.value = PlayMode.RANDOM
        setupVM().uiState.test { assertEquals(PlayMode.RANDOM, awaitItem().playMode) }
    }

    @Test fun `setPlayMode delegates`() = runTest {
        every { settingsRepo.setPlayMode(any()) } just Runs
        setupVM().setPlayMode(PlayMode.SINGLE)
        verify { settingsRepo.setPlayMode(PlayMode.SINGLE) }
    }
}
