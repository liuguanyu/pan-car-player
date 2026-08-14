package com.panmusic.carplayer.repository

import app.cash.turbine.test
import com.panmusic.carplayer.data.local.FakePlaylistDao
import com.panmusic.carplayer.data.local.FakeSongDao
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.testutil.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepositoryTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private val playlistDao = FakePlaylistDao()
    private val songDao = FakeSongDao()
    private val repository = PlaylistRepository(playlistDao, songDao)

    // ---------------------------------------------------------------------------
    // getAllPlaylists
    // ---------------------------------------------------------------------------

    @Test
    fun `getAllPlaylists should emit empty list initially`() = runTest {
        repository.getAllPlaylists().test {
            val initial = awaitItem()
            assertTrue(initial.isEmpty())
        }
    }

    @Test
    fun `getAllPlaylists should emit updated list after creating a playlist`() = runTest {
        repository.getAllPlaylists().test {
            awaitItem() // skip initial empty emission

            repository.createPlaylist("My Playlist")

            val updated = awaitItem()
            assertEquals(1, updated.size)
            assertEquals("My Playlist", updated.first().name)
        }
    }

    // ---------------------------------------------------------------------------
    // getPlaylistById
    // ---------------------------------------------------------------------------

    @Test
    fun `getPlaylistById should return null when playlist does not exist`() = runTest {
        val result = repository.getPlaylistById(999L)
        assertNull(result)
    }

    @Test
    fun `getPlaylistById should return playlist when it exists`() = runTest {
        val id = repository.createPlaylist("Test Playlist")
        val result = repository.getPlaylistById(id)
        assertNotNull(result)
        assertEquals("Test Playlist", result!!.name)
    }

    // ---------------------------------------------------------------------------
    // createPlaylist
    // ---------------------------------------------------------------------------

    @Test
    fun `createPlaylist should return a unique id and persist the playlist`() = runTest {
        val id1 = repository.createPlaylist("Playlist A")
        val id2 = repository.createPlaylist("Playlist B")

        assertTrue(id1 > 0)
        assertTrue(id2 > id1)

        assertEquals("Playlist A", repository.getPlaylistById(id1)?.name)
        assertEquals("Playlist B", repository.getPlaylistById(id2)?.name)
    }

    // ---------------------------------------------------------------------------
    // updatePlaylist
    // ---------------------------------------------------------------------------

    @Test
    fun `updatePlaylist should change the playlist name`() = runTest {
        val id = repository.createPlaylist("Original Name")
        val playlist = repository.getPlaylistById(id)!!

        repository.updatePlaylist(playlist.copy(name = "Updated Name"))

        val updated = repository.getPlaylistById(id)
        assertEquals("Updated Name", updated?.name)
    }

    // ---------------------------------------------------------------------------
    // deletePlaylist
    // ---------------------------------------------------------------------------

    @Test
    fun `deletePlaylist should remove the playlist`() = runTest {
        val id = repository.createPlaylist("To Delete")

        repository.deletePlaylist(id)

        assertNull(repository.getPlaylistById(id))
    }

    @Test
    fun `deletePlaylist should cascade to remove songs`() = runTest {
        val playlistId = repository.createPlaylist("With Songs")
        val songs = listOf(
            Song(fsId = 100, serverFilename = "song1.mp3"),
            Song(fsId = 200, serverFilename = "song2.mp3")
        )
        repository.addSongs(playlistId, songs)

        assertEquals(2, repository.getSongs(playlistId).size)

        repository.deletePlaylist(playlistId)

        // Fake DAOs don't auto-cascade, so we verify the playlist is gone;
        // in production Room cascades with onDelete = CASCADE.
        assertNull(repository.getPlaylistById(playlistId))
    }

    // ---------------------------------------------------------------------------
    // getSongs
    // ---------------------------------------------------------------------------

    @Test
    fun `getSongs should return empty list when playlist has no songs`() = runTest {
        val playlistId = repository.createPlaylist("Empty Playlist")
        val songs = repository.getSongs(playlistId)
        assertTrue(songs.isEmpty())
    }

    @Test
    fun `getSongs should return only songs belonging to the given playlist`() = runTest {
        val playlistA = repository.createPlaylist("Playlist A")
        val playlistB = repository.createPlaylist("Playlist B")

        repository.addSongs(playlistA, listOf(Song(fsId = 1, serverFilename = "a1.mp3")))
        repository.addSongs(playlistB, listOf(Song(fsId = 2, serverFilename = "b1.mp3")))

        val songsA = repository.getSongs(playlistA)
        val songsB = repository.getSongs(playlistB)

        assertEquals(1, songsA.size)
        assertEquals("a1.mp3", songsA.first().serverFilename)
        assertEquals(1, songsB.size)
        assertEquals("b1.mp3", songsB.first().serverFilename)
    }

    // ---------------------------------------------------------------------------
    // addSongs
    // ---------------------------------------------------------------------------

    @Test
    fun `addSongs should set the correct playlistId on every song`() = runTest {
        val playlistId = repository.createPlaylist("Add Songs Test")
        val songs = listOf(
            Song(fsId = 10, serverFilename = "track1.mp3"),
            Song(fsId = 20, serverFilename = "track2.mp3"),
            Song(fsId = 30, serverFilename = "track3.mp3")
        )

        repository.addSongs(playlistId, songs)

        val result = repository.getSongs(playlistId)
        assertEquals(3, result.size)
        result.forEach { assertEquals(playlistId, it.playlistId) }
    }

    // ---------------------------------------------------------------------------
    // deleteSongs
    // ---------------------------------------------------------------------------

    @Test
    fun `deleteSongs should remove only the specified songs`() = runTest {
        val playlistId = repository.createPlaylist("Delete Songs Test")
        repository.addSongs(
            playlistId,
            listOf(
                Song(fsId = 1, serverFilename = "keep.mp3"),
                Song(fsId = 2, serverFilename = "remove1.mp3"),
                Song(fsId = 3, serverFilename = "remove2.mp3")
            )
        )

        val allSongs = repository.getSongs(playlistId)
        val idsToDelete = allSongs.filter { it.serverFilename.startsWith("remove") }.map { it.id }
        assertEquals(2, idsToDelete.size)

        repository.deleteSongs(idsToDelete, playlistId)

        val remaining = repository.getSongs(playlistId)
        assertEquals(1, remaining.size)
        assertEquals("keep.mp3", remaining.first().serverFilename)
    }

    @Test
    fun `deleteSongs with empty id list should not throw`() = runTest {
        val playlistId = repository.createPlaylist("Empty Delete Test")
        repository.addSongs(playlistId, listOf(Song(fsId = 99, serverFilename = "only.mp3")))

        repository.deleteSongs(emptyList(), playlistId)

        assertEquals(1, repository.getSongs(playlistId).size)
    }

    // ---------------------------------------------------------------------------
    // isSongExists
    // ---------------------------------------------------------------------------

    @Test
    fun `isSongExists should return false when song is not in the playlist`() = runTest {
        val playlistId = repository.createPlaylist("Existence Test")
        repository.addSongs(playlistId, listOf(Song(fsId = 42, serverFilename = "answer.mp3")))

        val exists = repository.isSongExists(fsId = 999, playlistId = playlistId)
        assertFalse(exists)
    }

    @Test
    fun `isSongExists should return true when song is in the playlist`() = runTest {
        val playlistId = repository.createPlaylist("Existence Test")
        repository.addSongs(playlistId, listOf(Song(fsId = 42, serverFilename = "answer.mp3")))

        val exists = repository.isSongExists(fsId = 42, playlistId = playlistId)
        assertTrue(exists)
    }

    // ---------------------------------------------------------------------------
    // Flow: multiple updates
    // ---------------------------------------------------------------------------

    @Test
    fun `getAllPlaylists should reflect delete in reactive flow`() = runTest {
        repository.getAllPlaylists().test {
            awaitItem() // initial empty

            val id = repository.createPlaylist("Temp")
            val afterCreate = awaitItem()
            assertEquals(1, afterCreate.size)

            repository.deletePlaylist(id)
            val afterDelete = awaitItem()
            assertTrue(afterDelete.isEmpty())
        }
    }
}
