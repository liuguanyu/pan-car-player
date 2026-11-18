package com.baidu.carplayer.playback;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.player.PlaybackQueue;
import com.baidu.carplayer.repository.DownloadUrlRepository;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * NextUpPreloader 行为测试：
 * 启用条件、队列变更跟随、缓存命中跳过、换模式后旧预取取消。
 */
public class NextUpPreloaderTest {

    /** 记录请求与缓存的假仓库。 */
    private static class FakeRepo implements DownloadUrlRepository {
        final List<Long> requested = new ArrayList<>();
        final List<Long> cancelled = new ArrayList<>();
        final java.util.Map<Long, String> cache = new java.util.HashMap<>();
        boolean failNext = false;

        @Override
        public Cancellable resolve(long fsId, Callback callback) {
            requested.add(fsId);
            final long f = fsId;
            if (failNext) {
                callback.onError("boom", false, null);
                return () -> { };
            }
            // 模拟异步：立刻回调成功并写入"缓存"
            String url = "http://fake/" + fsId;
            cache.put(fsId, url);
            callback.onSuccess(url);
            return () -> cancelled.add(f);
        }

        @Override
        public String getCachedUrl(long fsId) {
            return cache.get(fsId);
        }
    }

    private static List<Song> songs(int n) {
        List<Song> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Song(1000L + i, "p", "s" + i, "/m/" + i + ".mp3", 1L, 0L));
        }
        return list;
    }

    @Test
    public void idlePreloaderMakesNoRequests() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(3));
        FakeRepo repo = new FakeRepo();
        NextUpPreloader preloader = new NextUpPreloader(queue, repo);

        // 未出声前，队列变更不应触发请求
        queue.setMode(PlaybackQueue.Mode.SINGLE);
        queue.moveTo(1);
        assertEquals(0, repo.requested.size());

        preloader.setEnabled(true);
        // 单曲模式：预取当前歌（fsId=1001）
        assertEquals(1, repo.requested.size());
        assertEquals(1001L, repo.requested.get(0).longValue());
    }

    @Test
    public void orderModePrefetchesNextAndFollowsModeChange() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(4));
        FakeRepo repo = new FakeRepo();
        NextUpPreloader preloader = new NextUpPreloader(queue, repo);

        preloader.setEnabled(true);
        assertEquals("顺序模式预取下一首", java.util.Collections.singletonList(1001L), repo.requested);

        // 换模式：旧预取作废，按新模式重取（当前歌 1000）
        queue.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(2, repo.requested.size());
        assertEquals(1000L, repo.requested.get(1).longValue());
    }

    @Test
    public void cachedSongIsNotPrefetchedAgain() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(3));
        FakeRepo repo = new FakeRepo();
        // 预置缓存：下一首已在缓存
        repo.cache.put(1001L, "http://fake/1001");
        NextUpPreloader preloader = new NextUpPreloader(queue, repo);

        preloader.setEnabled(true);
        assertTrue("缓存命中不发起请求", repo.requested.isEmpty());

        // 队列变更也不应重复请求
        queue.setMode(PlaybackQueue.Mode.SINGLE); // 目标变为当前歌 1000（未缓存）
        assertEquals(1, repo.requested.size());
        assertEquals(1000L, repo.requested.get(0).longValue());
    }

    @Test
    public void inFlightPrefetchSurvivesQueueChange() {
        // 切歌瞬间不应把在途预取取消掉：那等于白跑一次请求再加一次请求。
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(4));
        final FakeRepo repo = new FakeRepo();
        final java.util.List<Long> cancelled = new java.util.ArrayList<>();
        DownloadUrlRepository pendingRepo = new DownloadUrlRepository() {
            @Override
            public Cancellable resolve(long fsId, Callback callback) {
                return resolvePrefetch(fsId, callback);
            }

            @Override
            public Cancellable resolvePrefetch(long fsId, Callback callback) {
                repo.requested.add(fsId);
                final long f = fsId;
                return () -> cancelled.add(f);
            }

            @Override
            public String getCachedUrl(long fsId) {
                return null;
            }
        };
        NextUpPreloader preloader = new NextUpPreloader(queue, pendingRepo);
        preloader.setEnabled(true);

        queue.moveTo(1); // 切歌：下一个目标变成 1002
        assertEquals(2, repo.requested.size());
        assertEquals(1002L, repo.requested.get(1).longValue());
        assertTrue("队列变更不取消在途预取", cancelled.isEmpty());

        // 同一目标不会重复发请求
        queue.setMode(PlaybackQueue.Mode.ORDER);
        assertEquals(2, repo.requested.size());

        // 停用（暂停/缓冲）才真正取消
        preloader.setEnabled(false);
        assertEquals(2, cancelled.size());
        assertTrue(cancelled.contains(1001L));
        assertTrue(cancelled.contains(1002L));
    }

    @Test
    public void disableCancelsInFlightPrefetch() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(3));
        final FakeRepo repo = new FakeRepo();
        // 自定义仓库：resolve 不立即回调，返回可取消句柄
        DownloadUrlRepository pendingRepo = new DownloadUrlRepository() {
            @Override
            public Cancellable resolve(long fsId, Callback callback) {
                repo.requested.add(fsId);
                final long f = fsId;
                return () -> {
                    repo.cancelled.add(f);
                    callback.onError("cancelled", false, null);
                };
            }

            @Override
            public String getCachedUrl(long fsId) {
                return null;
            }
        };
        NextUpPreloader preloader = new NextUpPreloader(queue, pendingRepo);

        preloader.setEnabled(true);
        assertEquals(1, repo.requested.size());
        preloader.setEnabled(false);
        assertEquals("暂停时取消未完成的预取", java.util.Collections.singletonList(1000L + 1), repo.cancelled);

        // 停后队列变更不应再发起
        queue.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(1, repo.requested.size());
    }

    @Test
    public void prefetchFailureIsSilentAndRetryable() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(3));
        FakeRepo repo = new FakeRepo();
        NextUpPreloader preloader = new NextUpPreloader(queue, repo);

        repo.failNext = true;
        preloader.setEnabled(true);
        // 失败后请求已记录，无异常
        assertEquals(1, repo.requested.size());
        // 换模式触发重试
        queue.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(2, repo.requested.size());
        // 失败没有进缓存
        assertNull(repo.getCachedUrl(1000L));
    }

    @Test
    public void disposeUnsubscribesQueue() {
        PlaybackQueue queue = new PlaybackQueue();
        queue.setSongs(songs(3));
        FakeRepo repo = new FakeRepo();
        NextUpPreloader preloader = new NextUpPreloader(queue, repo);
        preloader.setEnabled(true);
        preloader.dispose();

        // 销毁后队列变更不再触发请求
        queue.setMode(PlaybackQueue.Mode.SINGLE);
        assertEquals(1, repo.requested.size());
    }
}
