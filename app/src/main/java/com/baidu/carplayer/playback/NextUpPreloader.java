package com.baidu.carplayer.playback;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.player.PlaybackQueue;
import com.baidu.carplayer.repository.DownloadUrlRepository;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 下一首下载地址预取器：订阅队列变更，静默获取"下一首"的可播放 URL，
 * 使切歌只剩缓冲时间。
 *
 * 纯 Java，不依赖 Service/Android；"下一首"完全由 {@link PlaybackQueue#peekNext()}
 * 的队列语义决定（顺序环绕/单曲当前歌/随机洗牌预览）。缓存 key 是 fsId（文件身份），
 * 因此列表重排、换模式最多浪费一次请求，永远不会拿到过期目标。
 *
 * 两条重要的行为约束（都是为了让预取**绝不拖慢正在加载的歌**）：
 * 1. 何时预取由 Service 决定：只有当前曲目稳定播放之后才会 {@link #setEnabled}；
 *    缓冲/暂停时会关闭预取，避免后台接口请求跟大体积无损文件抢带宽与接口配额。
 * 2. 队列变更不再取消在途预取，而是"多目标并行 + 同目标去重"：切歌瞬间 Service
 *    会解析同一 fsId，若这里先把它取消，等于白跑一次请求再加一次请求。
 *    在途结果落地进仓库缓存，对后续播放只有好处。
 *
 * 线程约定：{@link #setEnabled(boolean)} 与队列变更回调都必须在主线程调用
 * （Service 的 ExoPlayer 监听器与 PlaybackQueue 变更都在主线程）。
 * 仓库回调可能来自后台线程，这里只做幂等的"完成"标记，无共享状态竞争。
 */
public final class NextUpPreloader {
    /** 在途预取：句柄可能晚于回调赋值（仓库可能同步回调缓存命中）。 */
    private static final class Pending {
        @Nullable
        DownloadUrlRepository.Cancellable handle;
    }

    private final PlaybackQueue queue;
    private final DownloadUrlRepository urlRepository;

    private final PlaybackQueue.ChangeListener changeListener = this::onQueueChanged;
    private boolean enabled = false;
    private final Map<Long, Pending> inFlight = new HashMap<>();

    public NextUpPreloader(PlaybackQueue queue, DownloadUrlRepository urlRepository) {
        this.queue = queue;
        this.urlRepository = urlRepository;
        queue.addChangeListener(changeListener);
    }

    /** 播放稳定后启用；暂停/缓冲/停止时停用并取消未完成的预取。 */
    public void setEnabled(boolean playing) {
        if (enabled == playing) {
            return;
        }
        enabled = playing;
        if (playing) {
            prefetch();
        } else {
            cancelAll();
        }
    }

    /** Service 销毁时调用：取消预取并退订队列变更。 */
    public void dispose() {
        cancelAll();
        queue.removeChangeListener(changeListener);
    }

    private void onQueueChanged() {
        if (!enabled) {
            return;
        }
        prefetch();
    }

    private void prefetch() {
        if (!enabled) {
            return;
        }
        int target = queue.peekNext();
        if (target == PlaybackQueue.STAY) {
            return;
        }
        Song song = queue.at(target);
        if (song == null) {
            return;
        }
        final long fsId = song.getFsId();
        if (urlRepository.getCachedUrl(fsId) != null) {
            return; // 已有缓存，无需预取
        }
        final Pending pending = new Pending();
        synchronized (inFlight) {
            if (inFlight.containsKey(fsId)) {
                return; // 同一首已在路上
            }
            inFlight.put(fsId, pending);
        }
        DownloadUrlRepository.Cancellable handle = urlRepository.resolvePrefetch(
                fsId, new DownloadUrlRepository.Callback() {
                    @Override
                    public void onSuccess(String playableUrl) {
                        // 静默成功：结果已进入仓库缓存，切歌时直接使用
                        markDone(fsId, pending);
                    }

                    @Override
                    public void onError(String message, boolean authExpired, @Nullable Throwable cause) {
                        // 静默失败：下次真切到它时会正常请求
                        markDone(fsId, pending);
                    }
                });
        pending.handle = handle;
    }

    private void markDone(long fsId, Pending pending) {
        synchronized (inFlight) {
            if (inFlight.get(fsId) == pending) {
                inFlight.remove(fsId);
            }
        }
    }

    private void cancelAll() {
        Map<Long, Pending> snapshot;
        synchronized (inFlight) {
            snapshot = new HashMap<>(inFlight);
            inFlight.clear();
        }
        for (Pending pending : snapshot.values()) {
            DownloadUrlRepository.Cancellable handle = pending.handle;
            if (handle != null) {
                handle.cancel(); // 对已完成的请求是空操作
            }
        }
    }
}
