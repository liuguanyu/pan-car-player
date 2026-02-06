package com.baidu.carplayer.database;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;

/**
 * 应用数据库类
 */
@Database(entities = {Playlist.class, Song.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract PlaylistDao playlistDao();
    public abstract SongDao songDao();
}