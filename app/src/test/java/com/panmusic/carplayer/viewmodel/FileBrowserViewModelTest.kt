package com.panmusic.carplayer.viewmodel

import app.cash.turbine.test
import com.panmusic.carplayer.model.FileItem
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.repository.FileRepository
import com.panmusic.carplayer.repository.PlaylistRepository
import com.panmusic.carplayer.testutil.MainCoroutineRule
import io.mockk.any
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.eq
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class FileBrowserViewModelTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var fileRepository: FileRepository
    private lateinit var playlistRepository: PlaylistRepository

    @Before
    fun setUp() {
        fileRepository = mockk(relaxed = true)
        playlistRepository = mockk(relaxed = true)
    }

    @Test
    fun `loadDirectory success - sets isLoading, currentPath, items, parentPath for root dir`() = runTest {
        val items = listOf(
            FileItem(fsId = 1, path = "/music", serverFilename = "music", isDir = 1),
            FileItem(fsId = 2, path = "/readme.txt", serverFilename = "readme.txt", isDir = 0)
        )
        coEvery { fileRepository.getFileList("/") } returns Result.success(items)

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.loadDirectory("/")

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertEquals("/", state.currentPath)
        assertEquals(2, state.items.size)
        assertEquals(items, state.items)
        assertNull(state.parentPath)
        assertNull(state.error)
    }

    @Test
    fun `loadDirectory success - correct parentPath for subdir`() = runTest {
        coEvery { fileRepository.getFileList("/music/rock") } returns Result.success(emptyList())

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.loadDirectory("/music/rock")

        val state = viewModel.uiState.value
        assertEquals("/music/rock", state.currentPath)
        assertEquals("/music", state.parentPath)
    }

    @Test
    fun `loadDirectory failure - sets error`() = runTest {
        coEvery { fileRepository.getFileList("/") } returns Result.failure(
            RuntimeException("Permission denied")
        )

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.loadDirectory("/")

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertEquals("Permission denied", state.error)
    }

    @Test
    fun `toggleSelection select - adds item to selectedItems`() = runTest {
        val item = FileItem(fsId = 1, path = "/song.mp3", serverFilename = "song.mp3")
        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.toggleSelection(item)

        val state = viewModel.uiState.value
        assertEquals(1, state.selectedItems.size)
        assertEquals(item, state.selectedItems.first())
    }

    @Test
    fun `toggleSelection deselect - removes item from selectedItems`() = runTest {
        val item = FileItem(fsId = 1, path = "/song.mp3", serverFilename = "song.mp3")
        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        // first select
        viewModel.toggleSelection(item)
        assertEquals(1, viewModel.uiState.value.selectedItems.size)

        // then deselect
        viewModel.toggleSelection(item)

        assertEquals(0, viewModel.uiState.value.selectedItems.size)
    }

    @Test
    fun `scanAndAddToPlaylist success - emits ScanComplete event`() = runTest {
        val songs = listOf(
            Song(fsId = 1, serverFilename = "track1.mp3", path = "/music/track1.mp3")
        )
        coEvery { fileRepository.scanFolder("/music") } returns Result.success(songs)
        coEvery { playlistRepository.addSongs(1, songs) } returns Unit

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.events.test {
            viewModel.scanAndAddToPlaylist(1, "/music")

            val event = awaitItem()
            assertEquals(FileBrowserEvent.ScanComplete(1), event)
            assertEquals(false, viewModel.uiState.value.isScanning)
        }
    }

    @Test
    fun `scanAndAddToPlaylist failure - emits Error event`() = runTest {
        coEvery { fileRepository.scanFolder("/music") } returns Result.failure(
            RuntimeException("Scan failed")
        )

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.events.test {
            viewModel.scanAndAddToPlaylist(1, "/music")

            val event = awaitItem()
            assertEquals(FileBrowserEvent.Error("Scan failed"), event)
            assertEquals(false, viewModel.uiState.value.isScanning)
        }
    }

    @Test
    fun `addSelectedToPlaylist with empty selection - does nothing`() = runTest {
        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        viewModel.addSelectedToPlaylist(1)

        // state should remain at default (no coroutine launched since return@launch)
        val state = viewModel.uiState.value
        assertEquals(false, state.isScanning)
        assertEquals(emptyList<FileItem>(), state.selectedItems)
    }

    @Test
    fun `addSelectedToPlaylist with audio files - adds songs via playlistRepo and emits ScanComplete`() = runTest {
        val audioItem = FileItem(
            fsId = 1,
            path = "/song.mp3",
            serverFilename = "song.mp3",
            isDir = 0
        )
        coEvery { playlistRepository.addSongs(any(), any()) } returns Unit

        val viewModel = FileBrowserViewModel(fileRepository, playlistRepository)

        // pre-select the audio item
        viewModel.toggleSelection(audioItem)
        assertEquals(1, viewModel.uiState.value.selectedItems.size)

        viewModel.events.test {
            viewModel.addSelectedToPlaylist(1)

            val event = awaitItem()
            assertEquals(FileBrowserEvent.ScanComplete(1), event)

            val state = viewModel.uiState.value
            assertEquals(false, state.isScanning)
            assertTrue(state.selectedItems.isEmpty())

            coVerify(exactly = 1) { playlistRepository.addSongs(eq(1), any()) }
        }
    }
}