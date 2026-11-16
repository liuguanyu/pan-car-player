package com.baidu.carplayer.playback;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.player.PlaybackQueue;
import com.baidu.carplayer.repository.DownloadUrlRepository;

import androidx.annotation.Nullable;

/**
 * 下一首下载地址预取器：订阅队列变更，在真正出声时静默获取"下一首"的可播放 URL，
 * 使切歌只剩缓冲时间。
 *
 * 纯 Java，不依赖 Service/Android；"下一首"完全由 {@link PlaybackQueue#peekNext()}
 * 的队列语义决定（顺序环绕/单曲当前歌/随机洗牌预览）。缓存 key 是 fsId（文件身份），
 * 因此列表重排、换模式最多浪费一次请求，永远不会拿到过期目标。
 *
 * 线程约定：{@link #setEnabled(boolean)} 与队列变更回调都必须在主线程调用
 * （Service 的 ExoPlayer 监听器与 PlaybackQueue 变更都在主线程）。
 * 仓库回调可能来自后台线程，这里只做幂等的"完成"标记，无共享状态竞争。
 */
public final class NextUpPreloader {
    private final PlaybackQueue queue;
    private final DownloadUrlRepository urlRepository;

    private final PlaybackQueue.ChangeListener changeListener = this::onQueueChanged;
    private boolean enabled = false;
    @Nullable
    private DownloadUrlRepository.Cancellable inFlight;
    private long inFlightFsId = -1;

    public NextUpPreloader(PlaybackQueue queue, DownloadUrlRepository urlRepository) {
        this.queue = queue;
        this.urlRepository = urlRepository;
        queue.addChangeListener(changeListener);
    }

    /** 播放真正开始（出声）时启用；暂停/停止时停用并取消未完成的预取。 */
    public void setEnabled(boolean playing) {
        if (enabled == playing) {
            return;
        }
        enabled = playing;
        if (playing) {
            prefetch();
        } else {
            cancel();
        }
    }

    /** Service 销毁时调用：取消预取并退订队列变更。 */
    public void dispose() {
        cancel();
        queue.removeChangeListener(changeListener);
    }

    private void onQueueChanged() {
        if (!enabled) {
            return;
        }
        cancel();
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
        long fsId = song.getFsId();
        if (urlRepository.getCachedUrl(fsId) != null) {
            return; // 已有缓存，无需预取
        }
        inFlight = urlRepository.resolve(fsId, new DownloadUrlRepository.Callback() {
            @Override
            public void onSuccess(String playableUrl) {
                // 静默成功：结果已进入仓库缓存，切歌时直接使用
                markDone(fsId);
            }

            @Override
            public void onError(String message, boolean authExpired, @Nullable Throwable cause) {
                // 静默失败：下次真切到它时会正常请求
                markDone(fsId);
            }
        });
        inFlightFsId = fsId;
    }

    private void markDone(long fsId) {
        // 仅当仍是同一首在途预取时清空（回调可能来自后台线程，竞争后果无害：
        // Cancellable.cancel 对已完成的请求是空操作）
        if (fsId == inFlightFsId) {
            inFlight = null;
            inFlightFsId = -1;
        }
    }

    private void cancel() {
        if (inFlight != null) {
            inFlight.cancel();
            inFlight = null;
        }
        inFlightFsId = -1;
    }
}
