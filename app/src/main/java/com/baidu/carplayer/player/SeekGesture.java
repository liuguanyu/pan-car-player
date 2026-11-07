package com.baidu.carplayer.player;

import androidx.annotation.Nullable;

import com.baidu.carplayer.playback.PlaybackCommand;

/**
 * 进度条拖动手势的纯状态机，不持有 View。
 * <ul>
 *   <li>拖动中：忽略服务发布的位置，避免进度条在手指下跳动；</li>
 *   <li>松手：只生成一个 SEEK 命令，不改变播放/暂停意图（暂停时拖动仍保持暂停）；</li>
 *   <li>松手后到服务确认前：继续显示目标位置，避免回跳到旧位置再跳过去；
 *       换歌或连续若干次状态仍未确认（例如 seek 失败）则放弃等待。</li>
 * </ul>
 */
public final class SeekGesture {
    static final long CONFIRM_TOLERANCE_MS = 1500L;
    static final int MAX_UNCONFIRMED_UPDATES = 4;

    private boolean dragging;
    private long pendingTargetMs = -1L;
    private long pendingSongId = -1L;
    private int unconfirmedUpdates;

    public void start() {
        dragging = true;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** 松手时调用；返回需要分发的 SEEK 命令，没有正在拖动或没有歌曲时返回 null。 */
    @Nullable
    public PlaybackCommand finish(long songId, long targetMs) {
        if (!dragging) return null;
        dragging = false;
        if (songId <= 0) return null;
        pendingTargetMs = Math.max(0L, targetMs);
        pendingSongId = songId;
        unconfirmedUpdates = 0;
        return PlaybackCommand.seek(0L, pendingTargetMs);
    }

    /** 取消拖动（例如页面停止），不发送 seek。 */
    public void cancel() {
        dragging = false;
        clearPending();
    }

    /**
     * 根据服务发布的状态计算进度条应显示的位置。
     * @return 应显示的位置；返回 -1 表示保持进度条当前值不动（正在拖动）
     */
    public long displayPosition(PlayerViewState view) {
        if (dragging) return -1L;
        if (pendingTargetMs >= 0) {
            boolean confirmed = Math.abs(view.positionMs - pendingTargetMs) <= CONFIRM_TOLERANCE_MS;
            if (view.songId != pendingSongId || confirmed
                    || ++unconfirmedUpdates > MAX_UNCONFIRMED_UPDATES) {
                clearPending();
            } else {
                return pendingTargetMs;
            }
        }
        return view.positionMs;
    }

    private void clearPending() {
        pendingTargetMs = -1L;
        pendingSongId = -1L;
        unconfirmedUpdates = 0;
    }
}
