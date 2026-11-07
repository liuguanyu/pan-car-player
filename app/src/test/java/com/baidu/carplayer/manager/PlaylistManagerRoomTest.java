package com.baidu.carplayer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.baidu.carplayer.database.AppDatabase;
import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.testing.FakePanService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** 在真实 Room/SQLite（含外键 CASCADE）上验证歌单写入路径。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaylistManagerRoomTest {
    private static final Executor DIRECT = Runnable::run;

    private AppDatabase db;
    private PlaylistManager manager;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
        manager = new PlaylistManager(DatabaseManager.forDatabase(db), DIRECT, DIRECT);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private Playlist createPlaylist() {
        AtomicReference<Object> result = new AtomicReference<>();
        manager.createPlaylist("p", listener(result));
        return (Playlist) result.get();
    }

    private static Song song(long fsId) {
        return new Song(fsId, "", "s" + fsId, "/music/s" + fsId + ".mp3", 1L, 0L);
    }

    private static PlaylistManager.OnResultListener listener(AtomicReference<Object> out) {
        return new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) { out.set(result); }
            @Override public void onError(String error) { throw new AssertionError(error); }
        };
    }

    /** 回归：更新歌曲数量曾用 REPLACE，触发外键级联删除，导致刚添加的歌曲消失。 */
    @Test
    public void addingSongsKeepsAllSongsAndCount() {
        Playlist playlist = createPlaylist();
        for (long id = 1; id <= 3; id++) {
            manager.addSongToPlaylist(playlist.getId(), song(id), listener(new AtomicReference<>()));
        }
        assertEquals(3, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
        assertEquals(3, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());
    }

    @Test
    public void renamingPlaylistKeepsSongs() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        Playlist renamed = db.playlistDao().getPlaylistById(playlist.getId());
        renamed.setName("renamed");
        manager.updatePlaylist(renamed, listener(new AtomicReference<>()));
        assertEquals("renamed", db.playlistDao().getPlaylistById(playlist.getId()).getName());
        assertEquals(1, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
    }

    @Test
    public void removingSongsRecountsAndListingRepairsCount() {
        Playlist playlist = createPlaylist();
        for (long id = 1; id <= 3; id++) {
            manager.addSongToPlaylist(playlist.getId(), song(id), listener(new AtomicReference<>()));
        }
        Song first = db.songDao().getSong(1, playlist.getId());
        manager.removeSongsFromPlaylist(Arrays.asList(first), playlist.getId(), listener(new AtomicReference<>()));
        assertEquals(2, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());

        db.playlistDao().updateSongCount(playlist.getId(), 99);
        AtomicReference<Object> unused = new AtomicReference<>();
        manager.getAllPlaylists(new PlaylistManager.OnPlaylistsLoadListener() {
            @Override public void onSuccess(java.util.List<Playlist> playlists) { unused.set(playlists); }
            @Override public void onError(String error) { throw new AssertionError(error); }
        });
        assertEquals(2, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());
        assertEquals(2, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
    }

    @Test
    public void deletingPlaylistRemovesSongs() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        manager.deletePlaylist(playlist.getId(), listener(new AtomicReference<>()));
        assertNull(db.playlistDao().getPlaylistById(playlist.getId()));
        assertEquals(0, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
    }

    @Test
    public void batchAddSkipsExistingAndCountsOnce() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        AtomicReference<Object> added = new AtomicReference<>();
        manager.addSongsToPlaylist(playlist.getId(), Arrays.asList(song(1), song(2), song(3), song(3)), listener(added));
        assertEquals(2, added.get());
        assertEquals(3, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());
    }

    /** 回归：歌单已被删除时，批量添加应明确失败而不是静默丢弃。 */
    @Test
    public void batchAddToMissingPlaylistReportsError() {
        AtomicReference<String> error = new AtomicReference<>();
        manager.addSongsToPlaylist("missing", Arrays.asList(song(1)), new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) { throw new AssertionError("should fail"); }
            @Override public void onError(String e) { error.set(e); }
        });
        assertEquals("播放列表不存在，可能已被删除", error.get());
    }

    @Test
    public void refreshReplacesSongsWithCompleteSnapshot() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        FakePanService pan = new FakePanService()
                .folder("/music", FakePanService.audio(10, "/music/x.mp3"), FakePanService.dir("/music/sub"))
                .folder("/music/sub", FakePanService.audio(11, "/music/sub/y.mp3"));
        AtomicReference<Object> result = new AtomicReference<>();
        manager.refreshPlaylist(playlist.getId(), pan.service(), "t", listener(result));
        assertEquals(2, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
        assertNull(db.songDao().getSong(1, playlist.getId()));
        assertEquals(2, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());
    }

    /** 部分目录失败时保留原列表（回滚语义）。 */
    @Test
    public void partialCloudFailureKeepsExistingSongs() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        manager.addSongToPlaylist(playlist.getId(), song(2), listener(new AtomicReference<>()));
        FakePanService pan = new FakePanService()
                .folder("/music", FakePanService.audio(10, "/music/x.mp3"), FakePanService.dir("/music/sub"))
                .failHttp("/music/sub");
        AtomicReference<String> error = new AtomicReference<>();
        manager.refreshPlaylist(playlist.getId(), pan.service(), "t", new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) { throw new AssertionError("should fail"); }
            @Override public void onError(String e) { error.set(e); }
        });
        assertTrue(error.get().contains("/music/sub"));
        assertEquals(2, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
        assertEquals(2, db.playlistDao().getPlaylistById(playlist.getId()).getSongCount());
    }

    @Test
    public void emptyCloudSnapshotKeepsExistingSongs() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), listener(new AtomicReference<>()));
        AtomicReference<String> error = new AtomicReference<>();
        manager.refreshPlaylist(playlist.getId(), new FakePanService().service(), "t", new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) { throw new AssertionError("should fail"); }
            @Override public void onError(String e) { error.set(e); }
        });
        assertEquals(1, db.songDao().getSongsByPlaylistSync(playlist.getId()).size());
    }
}
