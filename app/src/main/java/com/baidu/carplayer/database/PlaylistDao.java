package com.baidu.carplayer.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.baidu.carplayer.model.Playlist;

import java.util.List;

/**
 * 播放列表DAO接口
 */
@Dao
public interface PlaylistDao {
    /** 列表页顺序：拖动排序写入的 sortOrder 升序，同值时新列表在前。 */
    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, createdTime DESC")
    LiveData<List<Playlist>> getAllPlaylists();

    @Query("SELECT * FROM playlists ORDER BY sortOrder ASC, createdTime DESC")
    List<Playlist> getAllPlaylistsSync();

    @Query("SELECT * FROM playlists WHERE id = :id")
    Playlist getPlaylistById(String id);

    /** 当前最靠前的 sortOrder；空表返回 0，用于给新列表排到最前面。 */
    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM playlists")
    int minSortOrder();

    /**
     * 仅用于新建播放列表。注意：不能用 REPLACE 更新已存在的列表——
     * SQLite 的 REPLACE 是“先删后插”，会触发 songs 外键的 CASCADE 删除全部歌曲。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertPlaylist(Playlist playlist);

    /** 更新已存在的播放列表（原地 UPDATE，不触发级联删除）。 */
    @Update
    int updatePlaylist(Playlist playlist);

    /** 只改显示顺序，避免整行更新覆盖并发写入的歌曲数量。 */
    @Query("UPDATE playlists SET sortOrder = :sortOrder WHERE id = :id")
    int updateSortOrder(String id, int sortOrder);

    @Query("UPDATE playlists SET songCount = :songCount WHERE id = :id")
    int updateSongCount(String id, int songCount);

    /** 按 COUNT(*) 重算歌曲数量，必须在事务中与歌曲增删一起调用。 */
    @Query("UPDATE playlists SET songCount = (SELECT COUNT(*) FROM songs WHERE playlistId = :id) WHERE id = :id")
    int recountSongs(String id);

    @Delete
    void deletePlaylist(Playlist playlist);
    
    @Query("DELETE FROM playlists WHERE id = :id")
    void deletePlaylistById(String id);
}
