package com.panmusic.carplayer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.panmusic.carplayer.model.Song

@Dao
interface SongDao {

    @Query("SELECT * FROM songs WHERE playlist_id = :playlistId ORDER BY id ASC")
    suspend fun getSongsByPlaylistId(playlistId: Long): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: Long): Song?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(songs: List<Song>)

    @Query("DELETE FROM songs WHERE id IN (:songIds)")
    suspend fun deleteByIds(songIds: List<Long>)

    @Query("DELETE FROM songs WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylistId(playlistId: Long)

    @Query("SELECT COUNT(*) FROM songs WHERE fs_id = :fsId AND playlist_id = :playlistId")
    suspend fun existsByFsId(fsId: Long, playlistId: Long): Boolean
}
