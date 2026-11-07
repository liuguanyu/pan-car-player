package com.baidu.carplayer.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.testing.FakePanService;
import com.baidu.carplayer.utils.LrcParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public class BaiduLyricsRepositoryTest {
    private static final Executor DIRECT = Runnable::run;

    /** 手动推进的执行器，用于模拟"请求进行中被取消"。 */
    private static final class QueueExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();
        @Override public void execute(Runnable r) { tasks.add(r); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }

    private static Song song(String path) {
        return new Song(1L, "p", "t", path, 1L, 0L);
    }

    private final List<String> downloads = new ArrayList<>();

    private BaiduLyricsRepository repo(FakePanService pan, String token, Executor net, Executor cb) {
        return new BaiduLyricsRepository(pan.service(), () -> token, net, cb, url -> {
            downloads.add(url);
            return "[00:01.00]hello\n[00:02.00]world\n";
        });
    }

    private static final class Result implements LyricsRepository.Callback {
        final AtomicReference<List<LrcParser.LrcEntry>> loaded = new AtomicReference<>();
        final AtomicReference<String> failed = new AtomicReference<>();
        @Override public void onLoaded(List<LrcParser.LrcEntry> e) { loaded.set(e); }
        @Override public void onFailed(String m) { failed.set(m); }
    }

    @Test
    public void loadsSameNameLrcFromSongDirectory() {
        FakePanService pan = new FakePanService()
                .searchable("/music", "Song.LRC", 42L)
                .dlink(42L, "https://d/x?a=1");
        Result r = new Result();
        repo(pan, "tok", DIRECT, DIRECT).load(song("/music/Song.flac"), r);
        assertNotNull(r.loaded.get());
        assertEquals(2, r.loaded.get().size());
        assertEquals("https://d/x?a=1&access_token=tok", downloads.get(0));
        assertTrue(pan.requests.contains("search:/music/Song.lrc"));
    }

    @Test
    public void missingLrcReportsNotFound() {
        Result r = new Result();
        repo(new FakePanService(), "tok", DIRECT, DIRECT).load(song("/music/a.mp3"), r);
        assertNull(r.loaded.get());
        assertTrue(r.failed.get().contains("a.lrc"));
    }

    @Test
    public void noTokenFailsWithoutNetwork() {
        FakePanService pan = new FakePanService();
        Result r = new Result();
        repo(pan, "", DIRECT, DIRECT).load(song("/music/a.mp3"), r);
        assertEquals(BaiduLyricsRepository.NOT_LOGGED_IN, r.failed.get());
        assertTrue(pan.requests.isEmpty());
    }

    @Test
    public void cancelledLoadNeverCallsBack() {
        FakePanService pan = new FakePanService().searchable("/m", "a.lrc", 1L).dlink(1L, "https://d");
        QueueExecutor net = new QueueExecutor();
        QueueExecutor cb = new QueueExecutor();
        Result r = new Result();
        DownloadUrlRepository.Cancellable handle = repo(pan, "t", net, cb).load(song("/m/a.mp3"), r);
        handle.cancel();
        net.runAll();
        cb.runAll();
        assertNull(r.loaded.get());
        assertNull(r.failed.get());
        assertTrue("取消后不应发起网络请求", pan.requests.isEmpty());
    }

    @Test
    public void cancelAfterNetworkBeforeCallbackDropsResult() {
        FakePanService pan = new FakePanService().searchable("/m", "a.lrc", 1L).dlink(1L, "https://d");
        QueueExecutor cb = new QueueExecutor();
        Result r = new Result();
        DownloadUrlRepository.Cancellable handle = repo(pan, "t", DIRECT, cb).load(song("/m/a.mp3"), r);
        handle.cancel();
        cb.runAll();
        assertNull(r.loaded.get());
    }

    @Test
    public void callbacksRunOnCallbackExecutor() {
        FakePanService pan = new FakePanService().searchable("/m", "a.lrc", 1L).dlink(1L, "https://d");
        QueueExecutor cb = new QueueExecutor();
        Result r = new Result();
        repo(pan, "t", DIRECT, cb).load(song("/m/a.mp3"), r);
        assertNull("回调必须经由回调执行器派发", r.loaded.get());
        cb.runAll();
        assertNotNull(r.loaded.get());
    }

    @Test
    public void pathHelpers() {
        assertEquals("a.b", BaiduLyricsRepository.baseNameWithoutExtension("/x/a.b.mp3"));
        assertEquals("/", BaiduLyricsRepository.parentDirectory("/a.mp3"));
        assertEquals("/x/y", BaiduLyricsRepository.parentDirectory("/x/y/a.mp3"));
    }
}
