package com.panmusic.carplayer.viewmodel

import app.cash.turbine.test
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.repository.PlaylistRepository
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val playlistRepository: PlaylistRepository = mockk(relaxed = true)

    // ==================== Init ====================

    @Test
    fun `init loads playlists from repository Flow and updates uiState`() = runTest {
        val testPlaylists = listOf(
            Playlist(id = 1, name = "My Playlist", songCount = 3),
            Playlist(id = 2, name = "Favorites", songCount = 5)
        )
        every { playlistRepository.getAllPlaylists() } returns flowOf(testPlaylists)

        val viewModel = PlaylistViewModel(playlistRepository)

        assertEquals(testPlaylists, viewModel.uiState.value.playlists)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ==================== createPlaylist ====================

    @Test
    fun `createPlaylist delegates to repository and emits PlaylistCreated event`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        coEvery { playlistRepository.createPlaylist("New Playlist") } returns 1L
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.createPlaylist("New Playlist")

            assertEquals(PlaylistUiEvent.PlaylistCreated(1L), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { playlistRepository.createPlaylist("New Playlist") }
    }

    @Test
    fun `createPlaylist emits ShowError on failure`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        coEvery { playlistRepository.createPlaylist(any()) } throws RuntimeException("创建失败")
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.createPlaylist("Bad Name")

            assertEquals(PlaylistUiEvent.ShowError("创建失败"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== renamePlaylist ====================

    @Test
    fun `renamePlaylist delegates to repository and emits PlaylistRenamed event`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        val playlist = Playlist(id = 1, name = "Old Name")
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.renamePlaylist(playlist, "New Name")

            assertEquals(PlaylistUiEvent.PlaylistRenamed, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { playlistRepository.updatePlaylist(playlist.copy(name = "New Name")) }
    }

    @Test
    fun `renamePlaylist emits ShowError on failure`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        coEvery { playlistRepository.updatePlaylist(any()) } throws RuntimeException("重命名失败")
        val playlist = Playlist(id = 1, name = "Old Name")
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.renamePlaylist(playlist, "Bad Name")

            assertEquals(PlaylistUiEvent.ShowError("重命名失败"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== deletePlaylist ====================

    @Test
    fun `deletePlaylist delegates to repository and emits PlaylistDeleted event`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.deletePlaylist(1L)

            assertEquals(PlaylistUiEvent.PlaylistDeleted, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { playlistRepository.deletePlaylist(1L) }
    }

    @Test
    fun `deletePlaylist emits ShowError on failure`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        coEvery { playlistRepository.deletePlaylist(any()) } throws RuntimeException("删除失败")
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.deletePlaylist(1L)

            assertEquals(PlaylistUiEvent.ShowError("删除失败"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ==================== loadSongs ====================

    @Test
    fun `loadSongs loads songs and emits SongsLoaded event`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        val songs = listOf(
            Song(id = 1, playlistId = 1, serverFilename = "song1.mp3", fsId = 100),
            Song(id = 2, playlistId = 1, serverFilename = "song2.mp3", fsId = 200)
        )
        coEvery { playlistRepository.getSongs(1L) } returns songs
        val viewModel = PlaylistViewModel(playlistRepository)

        assertTrue(viewModel.uiState.value.isLoading) // initial state from init
        viewModel.events.test {
            viewModel.loadSongs(1L)

            val event = awaitItem()
            assertTrue(event is PlaylistUiEvent.SongsLoaded)
            val songsLoaded = event as PlaylistUiEvent.SongsLoaded
            assertEquals(1L, songsLoaded.playlistId)
            assertEquals(songs, songsLoaded.songs)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(songs, viewModel.uiState.value.currentSongs)
        assertFalse(viewModel.uiState.value.isLoadingSongs)
        coVerify(exactly = 1) { playlistRepository.getSongs(1L) }
    }

    @Test
    fun `loadSongs emits ShowError on failure`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        coEvery { playlistRepository.getSongs(any()) } throws RuntimeException("加载歌曲失败")
        val viewModel = PlaylistViewModel(playlistRepository)

        viewModel.events.test {
            viewModel.loadSongs(1L)

            assertEquals(PlaylistUiEvent.ShowError("加载歌曲失败"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.uiState.value.isLoadingSongs)
    }

    // ==================== refresh ====================

    @Test
    fun `refresh sets isLoading to true`() = runTest {
        every { playlistRepository.getAllPlaylists() } returns flowOf(emptyList())
        val viewModel = PlaylistViewModel(playlistRepository)
        // After init, isLoading should be false (collected empty list)
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.refresh()

        assertTrue(viewModel.uiState.value.isLoading)
    }
}
