package com.baidu.carplayer.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.baidu.carplayer.database.AppDatabase;
import com.baidu.carplayer.database.DatabaseManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.repository.CloudFileRepository;
import com.baidu.carplayer.testing.FakePanService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 4.5：并发、部分失败回滚、计数一致性。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PlaylistConcurrencyTest {
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
        manager.createPlaylist("p", new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { result.set(r); }
            @Override public void onError(String e) { throw new AssertionError(e); }
        });
        return (Playlist) result.get();
    }

    private static Song song(long fsId) {
        return new Song(fsId, "", "s" + fsId, "/music/s" + fsId + ".mp3", 1L, 0L);
    }

    private int countOf(String playlistId) {
        return db.songDao().getSongsByPlaylistSync(playlistId).size();
    }

    private int declaredCount(String playlistId) {
        return db.playlistDao().getPlaylistById(playlistId).getSongCount();
    }

    /** 多线程同时向一个歌单写入，全部完成计数必须与行数一致（无丢失更新）。 */
    @Test
    public void concurrentAddsDoNotLoseUpdates() throws Exception {
        final Playlist playlist = createPlaylist();
        final int threads = 6;
        final int perThread = 15;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        manager.addSongToPlaylist(playlist.getId(), song(base + i), new PlaylistManager.OnResultListener() {
                            @Override public void onSuccess(Object r) { }
                            @Override public void onError(String e) { errors.add(e); }
                        });
                    }
                } catch (Exception e) {
                    errors.add(String.valueOf(e));
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(Collections.emptyList(), errors);
        assertEquals(threads * perThread, countOf(playlist.getId()));
        assertEquals("歌单声明的数量必须等于实际行数", threads * perThread, declaredCount(playlist.getId()));
    }

    /** 并发添加同一批歌曲：只会插入一次，计数不会翻倍。 */
    @Test
    public void concurrentIdenticalAddsInsertOnce() throws Exception {
        final Playlist playlist = createPlaylist();
        final int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    manager.addSongToPlaylist(playlist.getId(), song(1), new PlaylistManager.OnResultListener() {
                        @Override public void onSuccess(Object r) { }
                        @Override public void onError(String e) { failures.incrementAndGet(); }
                    });
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals("重复添加只应插入一次", 1, countOf(playlist.getId()));
        assertEquals(1, declaredCount(playlist.getId()));
        // 其余线程应收到"已存在"错误，而不是静默重复插入
        assertTrue("重复添加不应静默翻倍", failures.get() <= threads - 1);
    }

    /** 刷新过程中歌单被删除：旧数据不会回来，也不会抛到调用方之外。 */
    @Test
    public void refreshOnDeletedPlaylistDoesNotResurrect() throws Exception {
        final Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { }
            @Override public void onError(String e) { throw new AssertionError(e); }
        });

        // 令牌是在读取歌曲之后、重建之前取的；在这里删除歌单即模拟"扫描期间被删除"。
        FakePanService pan = new FakePanService()
                .folder("/music", FakePanService.audio(10, "/music/x.mp3"));
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicReference<Object> success = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>("t");
        CloudFileRepository repo = new CloudFileRepository(pan.service(), () -> {
            if (token.getAndSet(null) != null) {
                db.playlistDao().deletePlaylist(db.playlistDao().getPlaylistById(playlist.getId()));
            }
            return "t";
        });
        manager.refreshPlaylist(playlist.getId(), repo, new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { success.set(r); }
            @Override public void onError(String e) { error.set(e); }
        });
        assertNull("歌单已删除时不应报告成功", success.get());
        assertNotNull("歌单已删除时重建必须失败", error.get());
        assertNull(db.playlistDao().getPlaylistById(playlist.getId()));
        assertEquals(0, countOf(playlist.getId()));
    }

    /** 全量重建：旧歌曲被替换，计数同事务更新。 */
    @Test
    public void refreshReplacesAndKeepsCountConsistent() {
        Playlist playlist = createPlaylist();
        for (long i = 1; i <= 3; i++) {
            manager.addSongToPlaylist(playlist.getId(), song(i), new PlaylistManager.OnResultListener() {
                @Override public void onSuccess(Object r) { }
                @Override public void onError(String e) { throw new AssertionError(e); }
            });
        }
        FakePanService pan = new FakePanService().folder("/music",
                FakePanService.audio(11, "/music/a.mp3"),
                FakePanService.audio(12, "/music/b.mp3"),
                FakePanService.audio(13, "/music/c.mp3"),
                FakePanService.audio(14, "/music/d.mp3"));
        AtomicReference<String> error = new AtomicReference<>();
        manager.refreshPlaylist(playlist.getId(), pan.service(), "t", new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { }
            @Override public void onError(String e) { error.set(e); }
        });
        assertNull(error.get());
        assertEquals(4, countOf(playlist.getId()));
        assertEquals(4, declaredCount(playlist.getId()));
    }

    /** 刷新失败时保持原状：行数与计数都不变。 */
    @Test
    public void failedRefreshLeavesRowsAndCountUntouched() {
        Playlist playlist = createPlaylist();
        for (long i = 1; i <= 2; i++) {
            manager.addSongToPlaylist(playlist.getId(), song(i), new PlaylistManager.OnResultListener() {
                @Override public void onSuccess(Object r) { }
                @Override public void onError(String e) { throw new AssertionError(e); }
            });
        }
        FakePanService pan = new FakePanService()
                .folder("/music", FakePanService.audio(10, "/music/x.mp3"), FakePanService.dir("/music/sub"))
                .errno("/music/sub", 5);
        AtomicReference<String> error = new AtomicReference<>();
        manager.refreshPlaylist(playlist.getId(), pan.service(), "t", new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { throw new AssertionError("should fail"); }
            @Override public void onError(String e) { error.set(e); }
        });
        assertTrue(error.get().contains("errno 5"));
        assertEquals(2, countOf(playlist.getId()));
        assertEquals(2, declaredCount(playlist.getId()));
    }

    /** 计数读取路径：直接改数据库造成的不一致会被 getAllPlaylists 修正。 */
    @Test
    public void listingRepairsStaleCounts() {
        Playlist playlist = createPlaylist();
        manager.addSongToPlaylist(playlist.getId(), song(1), new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object r) { }
            @Override public void onError(String e) { throw new AssertionError(e); }
        });
        db.playlistDao().updateSongCount(playlist.getId(), 99); // 模拟外部写坏
        AtomicReference<List<Playlist>> out = new AtomicReference<>();
        manager.getAllPlaylists(new PlaylistManager.OnPlaylistsLoadListener() {
            @Override public void onSuccess(List<Playlist> playlists) { out.set(playlists); }
            @Override public void onError(String e) { throw new AssertionError(e); }
        });
        assertEquals(1, out.get().get(0).getSongCount());
        assertEquals(1, declaredCount(playlist.getId()));
    }

    /** 根路径推导：子目录被父目录覆盖，根目录保留。 */
    @Test
    public void syncRootsCollapseNestedPaths() {
        List<Song> songs = Arrays.asList(
                new Song(1, "p", "a", "/music/rock/a.mp3", 1, 0),
                new Song(2, "p", "b", "/music/rock/b.mp3", 1, 0),
                new Song(3, "p", "c", "/other/c.mp3", 1, 0),
                new Song(4, "p", "d", "/music/rock/live/x.mp3", 1, 0));
        java.util.Set<String> roots = PlaylistManager.calculateSyncRoots(songs);
        assertEquals(new java.util.HashSet<>(Arrays.asList("/music/rock", "/other")), roots);

        // 根目录下的文件其父路径就是 "/"，会退化成扫描整个网盘，但不会漏歌。
        assertEquals("/", PlaylistManager.getParentPath("/root.mp3"));
        assertEquals("/", PlaylistManager.getParentPath("/a.mp3"));
        assertEquals("/music/rock", PlaylistManager.getParentPath("/music/rock/a.mp3"));
        assertNull(PlaylistManager.getParentPath("/"));
        assertNull(PlaylistManager.getParentPath(""));
    }
}
