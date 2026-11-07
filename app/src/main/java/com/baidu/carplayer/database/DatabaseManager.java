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
        )
        // 本地播放列表可从网盘重建；schema 升级时直接重建数据库，不维护迁移。
        .fallbackToDestructiveMigration()
        .build();
    }

    private DatabaseManager(AppDatabase database) {
        this.database = database;
    }

    /** 包装已有数据库实例（如内存数据库），用于测试；不影响全局单例。 */
    public static DatabaseManager forDatabase(AppDatabase database) {
        return new DatabaseManager(database);
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