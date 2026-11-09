package com.baidu.carplayer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.baidu.carplayer.backup.BackupPlaylist;
import com.baidu.carplayer.backup.ImportNaming;
import com.baidu.carplayer.database.AppDatabase;
import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaylistImportTest {
    private static final Executor DIRECT = Runnable::run;
    private AppDatabase db;
    private PlaylistManager manager;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
        manager = new PlaylistManager(DatabaseManager.forDatabase(db), DIRECT, DIRECT);
    }

    @After public void tearDown() { db.close(); }

    private static Song song(long fsId) {
        Song s = new Song(fsId, null, "s" + fsId + ".mp3", "/m/s" + fsId + ".mp3", 10, 0);
        s.setDuration(fsId * 1000);
        return s;
    }

    private static BackupPlaylist backup(String name, long... ids) {
        List<Song> songs = new ArrayList<>();
        for (long id : ids) songs.add(song(id));
        return new BackupPlaylist(name, "", songs, 0, 0);
    }

    @Test public void namingRules() {
        assertEquals("A", ImportNaming.uniqueName("A", Collections.emptySet()));
        assertEquals("A (导入)", ImportNaming.uniqueName("A", new HashSet<>(Arrays.asList("A"))));
        assertEquals("A (导入2)", ImportNaming.uniqueName("A", new HashSet<>(Arrays.asList("A", "A (导入)"))));
        assertEquals("A (导入3)", ImportNaming.uniqueName("A",
                new HashSet<>(Arrays.asList("A", "A (导入)", "A (导入2)"))));
    }

    @Test public void importsInFileOrderAndKeepsExisting() {
        Playlist existing = new Playlist("old", "生肖", 1);
        db.playlistDao().insertPlaylist(existing);
        db.songDao().insertSongs(Collections.singletonList(new Song(99, "old", "x", "/x", 1, 1)));
        db.playlistDao().recountSongs("old");

        List<PlaylistManager.ImportedPlaylist> r = manager.importPlaylistsNow(Arrays.asList(
                backup("生肖", 30, 10, 20, 10), backup("周.转", 5), backup("生肖", 7)), 1000);

        assertEquals("生肖 (导入)", r.get(0).name);
        assertEquals(3, r.get(0).songCount);                 // 重复 fs_id 去重
        assertEquals("周.转", r.get(1).name);
        assertEquals("生肖 (导入2)", r.get(2).name);          // 同批次也避让

        List<Song> songs = db.songDao().getSongsByPlaylistSync(r.get(0).id);
        assertEquals(Arrays.asList(30L, 10L, 20L),
                Arrays.asList(songs.get(0).getFsId(), songs.get(1).getFsId(), songs.get(2).getFsId()));
        assertEquals(30000L, songs.get(0).getDuration());
        assertEquals(3, db.playlistDao().getPlaylistById(r.get(0).id).getSongCount());

        // 原列表未被改动
        assertEquals("生肖", db.playlistDao().getPlaylistById("old").getName());
        assertEquals(1, db.songDao().getSongsByPlaylistSync("old").size());

        // 列表页按 createdTime DESC 排列，导入的按文件顺序位于最前
        List<Playlist> all = db.playlistDao().getAllPlaylistsSync();
        assertEquals(Arrays.asList("生肖 (导入)", "周.转", "生肖 (导入2)", "生肖"),
                Arrays.asList(all.get(0).getName(), all.get(1).getName(), all.get(2).getName(), all.get(3).getName()));

        // 再导入一次 → (导入3)
        assertEquals("生肖 (导入3)", manager.importPlaylistsNow(
                Collections.singletonList(backup("生肖", 1)), 2000).get(0).name);
    }

    @Test public void failureRollsBackWholeImport() {
        List<BackupPlaylist> exploding = new AbstractList<BackupPlaylist>() {
            @Override public BackupPlaylist get(int i) {
                if (i == 2) throw new IllegalStateException("boom");
                return backup("L" + i, i + 1);
            }
            @Override public int size() { return 3; }
        };
        try {
            manager.importPlaylistsNow(exploding, 1000);
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
        assertTrue(db.playlistDao().getAllPlaylistsSync().isEmpty());
        assertTrue(db.songDao().getSongsByPlaylistSync("L0").isEmpty());
    }

    @Test public void asyncImportReportsResult() {
        Object[] out = new Object[1];
        manager.importPlaylists(Collections.singletonList(backup("A", 1, 2)), new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) { out[0] = result; }
            @Override public void onError(String error) { throw new AssertionError(error); }
        });
        assertEquals(1, ((List<?>) out[0]).size());
    }
}
