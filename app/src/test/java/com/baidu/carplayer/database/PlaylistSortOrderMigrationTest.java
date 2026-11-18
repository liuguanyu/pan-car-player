package com.baidu.carplayer.database;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.baidu.carplayer.model.Playlist;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * 3 → 4 迁移：升级后已有歌单必须保留（不能重建库），
 * 并按原来的 createdTime DESC 观感换算 sortOrder。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaylistSortOrderMigrationTest {
    private static final String DB_NAME = "migration-sort-order.db";

    private Context context;
    private AppDatabase db;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB_NAME);
        createLegacyDatabase();
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
        context.deleteDatabase(DB_NAME);
    }

    /** 用 v3 的建表语句（playlists 没有 sortOrder）造一个升级前的数据库。 */
    private void createLegacyDatabase() {
        SQLiteOpenHelper helper = new SQLiteOpenHelper(context, DB_NAME, null, 3) {
            @Override
            public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` TEXT NOT NULL, `name` TEXT, "
                        + "`createdTime` INTEGER NOT NULL, `songCount` INTEGER NOT NULL, PRIMARY KEY(`id`))");
                db.execSQL("CREATE TABLE IF NOT EXISTS `songs` (`fsId` INTEGER NOT NULL, "
                        + "`playlistId` TEXT NOT NULL, `title` TEXT, `path` TEXT, `size` INTEGER NOT NULL, "
                        + "`addedTime` INTEGER NOT NULL, `artist` TEXT, `album` TEXT, "
                        + "`duration` INTEGER NOT NULL, `coverUrl` TEXT, PRIMARY KEY(`fsId`, `playlistId`), "
                        + "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) "
                        + "ON UPDATE NO ACTION ON DELETE CASCADE )");
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_songs_playlistId` ON `songs` (`playlistId`)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                // 升级前的库不会被测试再升一级
            }
        };
        SQLiteDatabase legacy = helper.getWritableDatabase();
        // createdTime 越大越靠前（v3 的展示顺序是 createdTime DESC）
        legacy.execSQL("INSERT INTO playlists (id, name, createdTime, songCount) VALUES ('a', 'A', 100, 1)");
        legacy.execSQL("INSERT INTO playlists (id, name, createdTime, songCount) VALUES ('b', 'B', 200, 0)");
        legacy.execSQL("INSERT INTO playlists (id, name, createdTime, songCount) VALUES ('c', 'C', 300, 1)");
        legacy.execSQL("INSERT INTO songs (fsId, playlistId, title, path, size, addedTime, artist, album, "
                + "duration, coverUrl) VALUES (7, 'a', 'x', '/x.mp3', 1, 1, NULL, NULL, 0, NULL)");
        helper.close();
    }

    private void openCurrentVersion() {
        db = Room.databaseBuilder(context, AppDatabase.class, DB_NAME)
                .addMigrations(DatabaseManager.MIGRATION_3_4)
                .allowMainThreadQueries()
                .build();
        // 触发实际打开与迁移
        db.playlistDao().getAllPlaylistsSync();
    }

    @Test
    public void upgradeKeepsPlaylistsInPreviousDisplayOrder() {
        openCurrentVersion();
        List<Playlist> all = db.playlistDao().getAllPlaylistsSync();
        assertEquals(3, all.size());
        assertEquals(Arrays.asList("C", "B", "A"),
                Arrays.asList(all.get(0).getName(), all.get(1).getName(), all.get(2).getName()));
        assertEquals(Arrays.asList(0, 1, 2),
                Arrays.asList(all.get(0).getSortOrder(), all.get(1).getSortOrder(), all.get(2).getSortOrder()));
    }

    @Test
    public void upgradeKeepsSongsAndCounts() {
        openCurrentVersion();
        assertEquals(1, db.songDao().getSongsByPlaylistSync("a").size());
        assertEquals(1, db.playlistDao().getPlaylistById("a").getSongCount());
    }
}
