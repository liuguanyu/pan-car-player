package com.baidu.carplayer.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

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
        // 本地播放列表可从网盘重建，所以只在“无法迁移”时才重建数据库；
        // 纯增列的升级（如 3→4 的 sortOrder）走显式迁移，保留用户已有列表。
        .addMigrations(MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .build();
    }

    /**
     * 3 → 4：新增拖动排序用的 sortOrder 列（纯增列，不破坏已有数据）。
     * 旧版本的展示顺序是 createdTime DESC，这里换算成等价的 sortOrder 保住原观感。
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE playlists SET sortOrder = "
                    + "(SELECT COUNT(*) FROM playlists AS ahead WHERE ahead.createdTime > playlists.createdTime)");
        }
    };

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