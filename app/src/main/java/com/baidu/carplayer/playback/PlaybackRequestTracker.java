package com.baidu.carplayer.playback;

import androidx.annotation.Nullable;

/**
 * 管理播放加载请求的代次和 seek/autoplay 意图。
 * 只在主线程使用；不依赖 Android 组件，便于对乱序响应、加载中暂停和销毁做单元测试。
 */
public final class PlaybackRequestTracker {
    private long generation = 0L;
    private boolean closed = false;
    @Nullable
    private PlaybackCommand active;

    /** 开始一次新的歌曲加载；之前的所有请求立即失效。 */
    public PlaybackCommand begin(long songId, @Nullable Long seekPositionMs, boolean playWhenReady) {
        generation++;
        PlaybackCommand command = PlaybackCommand.select(generation, songId, seekPositionMs, playWhenReady);
        active = closed ? null : command;
        return command;
    }

    /** 网络回调只有在请求仍是最新且未关闭时才能提交结果。 */
    public boolean isCurrent(long requestId) {
        return !closed && requestId == generation;
    }

    public long getGeneration() {
        return generation;
    }

    public boolean isClosed() {
        return closed;
    }

    /** 当前仍有效的加载命令；已失效或已关闭时返回 null。 */
    @Nullable
    public PlaybackCommand current() {
        PlaybackCommand command = active;
        return command != null && isCurrent(command.getRequestId()) ? command : null;
    }

    /** 加载中用户暂停/播放时，只修改当前请求的 autoplay 意图。 */
    public void setPlayWhenReady(boolean playWhenReady) {
        PlaybackCommand command = current();
        if (command != null) {
            active = command.withPlayWhenReady(playWhenReady);
        }
    }

    /** 取出待执行的 seek，并保证同一个 seek 只被执行一次。 */
    @Nullable
    public PlaybackCommand consumeSeek() {
        PlaybackCommand command = current();
        if (command == null || !command.hasSeekPosition()) {
            return null;
        }
        active = command.withoutSeek();
        return command;
    }

    /** 加载失败后清理当前命令，避免状态停留在缓冲中。 */
    public void finish(long requestId) {
        PlaybackCommand command = active;
        if (command != null && command.getRequestId() == requestId) {
            active = null;
        }
    }

    /** Service 销毁时调用：之后所有回调都会被丢弃。 */
    public void close() {
        closed = true;
        generation++;
        active = null;
    }
}
