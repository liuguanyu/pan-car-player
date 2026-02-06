package com.baidu.carplayer.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.baidu.carplayer.model.Playlist;

import java.util.List;

/**
 * 播放列表DAO接口
 */
@Dao
public interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdTime DESC")
    LiveData<List<Playlist>> getAllPlaylists();

    @Query("SELECT * FROM playlists ORDER BY createdTime DESC")
    List<Playlist> getAllPlaylistsSync();

    @Query("SELECT * FROM playlists WHERE id = :id")
    Playlist getPlaylistById(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertPlaylist(Playlist playlist);

    @Delete
    void deletePlaylist(Playlist playlist);
    
    @Query("DELETE FROM playlists WHERE id = :id")
    void deletePlaylistById(String id);
}