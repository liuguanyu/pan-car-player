package com.baidu.carplayer.database;

import android.content.Context;

import androidx.room.Room;

import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;

import java.util.List;

/**
 * 数据库管理器 - 单例模式
 */
public class DatabaseManager {
    private static final String DATABASE_NAME = "car_player_db";
    private static volatile DatabaseManager instance;
    private final AppDatabase database;

    private DatabaseManager(Context context) {
        database = Room.databaseBuilder(
                context.getApplicationContext(),
                AppDatabase.class,
                DATABASE_NAME
        ).build();
    }

    public static DatabaseManager getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = new DatabaseManager(context);
                }
            }
        }
        return instance;
    }

    public AppDatabase getDatabase() {
        return database;
    }
    
    public List<Playlist> getAllPlaylists() {
        return database.playlistDao().getAllPlaylistsSync();
    }
    
    public List<Song> getSongsForPlaylist(String playlistId) {
        return database.songDao().getSongsByPlaylistSync(playlistId);
    }
}