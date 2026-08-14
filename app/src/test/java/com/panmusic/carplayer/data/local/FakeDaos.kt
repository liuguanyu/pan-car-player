package com.panmusic.carplayer.data.local

import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePlaylistDao : com.panmusic.carplayer.data.local.PlaylistDao {
    private val playlists = mutableListOf<Playlist>()
    private val _flow = MutableStateFlow<List<Playlist>>(emptyList())
    private var nextId = 1L

    override fun getAllPlaylists(): Flow<List<Playlist>> = _flow
    override suspend fun getPlaylistById(id: Long): Playlist? = playlists.find { it.id == id }
    override suspend fun insert(playlist: Playlist): Long {
        val p = playlist.copy(id = nextId++)
        playlists.add(p)
        _flow.value = playlists.toList()
        return p.id
    }
    override suspend fun update(playlist: Playlist) {
        val idx = playlists.indexOfFirst { it.id == playlist.id }
        if (idx >= 0) { playlists[idx] = playlist; _flow.value = playlists.toList() }
    }
    override suspend fun delete(playlist: Playlist) { playlists.removeAll { it.id == playlist.id }; _flow.value = playlists.toList() }
    override suspend fun deleteById(id: Long) { playlists.removeAll { it.id == id }; _flow.value = playlists.toList() }
    override suspend fun updateSongCount(playlistId: Long) {}
}

class FakeSongDao : com.panmusic.carplayer.data.local.SongDao {
    private val songs = mutableListOf<Song>()
    private var nextId = 1L

    override suspend fun getSongsByPlaylistId(playlistId: Long): List<Song> = songs.filter { it.playlistId == playlistId }
    override suspend fun getSongById(id: Long): Song? = songs.find { it.id == id }
    override suspend fun insertAll(newSongs: List<Song>) { songs.addAll(newSongs.map { it.copy(id = nextId++) }) }
    override suspend fun deleteByIds(songIds: List<Long>) { songs.removeAll { it.id in songIds } }
    override suspend fun deleteByPlaylistId(playlistId: Long) { songs.removeAll { it.playlistId == playlistId } }
    override suspend fun existsByFsId(fsId: Long, playlistId: Long): Boolean = songs.any { it.fsId == fsId && it.playlistId == playlistId }
}
