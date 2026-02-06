package com.baidu.carplayer.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.baidu.carplayer.model.Song;

import java.util.List;

/**
 * 歌曲DAO接口
 */
@Dao
public interface SongDao {
    @Query("SELECT * FROM songs WHERE playlistId = :playlistId ORDER BY addedTime ASC")
    LiveData<List<Song>> getSongsByPlaylist(String playlistId);

    @Query("SELECT * FROM songs WHERE playlistId = :playlistId ORDER BY addedTime ASC")
    List<Song> getSongsByPlaylistSync(String playlistId);

    @Query("SELECT * FROM songs WHERE fsId = :fsId")
    Song getSongById(long fsId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSong(Song song);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertSongs(List<Song> songs);

    @Delete
    void deleteSong(Song song);
    
    @Query("DELETE FROM songs WHERE fsId = :fsId")
    void deleteSongById(long fsId);
    
    @Query("DELETE FROM songs WHERE playlistId = :playlistId")
    void deleteSongsByPlaylist(String playlistId);
    
    @Query("SELECT COUNT(*) FROM songs WHERE playlistId = :playlistId")
    int getSongCount(String playlistId);
}