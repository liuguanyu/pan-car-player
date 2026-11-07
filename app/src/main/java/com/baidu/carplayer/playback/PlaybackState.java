package com.baidu.carplayer.playback;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.Song;

import java.util.Collections;
import java.util.List;

/** 不可变的播放状态快照，供页面和系统媒体入口观察。 */
public final class PlaybackState {
    public enum Status { IDLE, BUFFERING, READY, ENDED, ERROR }

    private final List<Song> queue;
    private final int currentIndex;
    private final long songId;
    private final Status status;
    private final boolean playWhenReady;
    private final long positionMs;
    private final long durationMs;
    private final int playMode;
    private final int audioSessionId;
    private final long requestId;
    @Nullable
    private final String errorMessage;
    private final float volume;
    @Nullable
    private final AudioFormat audioFormat;

    public PlaybackState(List<Song> queue, int currentIndex, long songId, Status status,
                         boolean playWhenReady, long positionMs, long durationMs,
                         int playMode, int audioSessionId, long requestId) {
        this(queue, currentIndex, songId, status, playWhenReady, positionMs, durationMs,
                playMode, audioSessionId, requestId, null);
    }

    public PlaybackState(List<Song> queue, int currentIndex, long songId, Status status,
                         boolean playWhenReady, long positionMs, long durationMs,
                         int playMode, int audioSessionId, long requestId,
                         @Nullable String errorMessage) {
        this(queue, currentIndex, songId, status, playWhenReady, positionMs, durationMs,
                playMode, audioSessionId, requestId, errorMessage, 1f, null);
    }

    private PlaybackState(List<Song> queue, int currentIndex, long songId, Status status,
                          boolean playWhenReady, long positionMs, long durationMs,
                          int playMode, int audioSessionId, long requestId,
                          @Nullable String errorMessage, float volume,
                          @Nullable AudioFormat audioFormat) {
        this.queue = snapshotQueue(queue);
        this.currentIndex = currentIndex;
        this.songId = songId;
        this.status = status == null ? Status.IDLE : status;
        this.playWhenReady = playWhenReady;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.playMode = playMode;
        this.audioSessionId = audioSessionId;
        this.requestId = requestId;
        this.errorMessage = errorMessage;
        this.volume = volume;
        this.audioFormat = audioFormat;
    }

    /** 附带用户音量和解码格式的新快照。 */
    public PlaybackState withPlayerDetails(float volume, @Nullable AudioFormat audioFormat) {
        return new PlaybackState(queue, currentIndex, songId, status, playWhenReady, positionMs,
                durationMs, playMode, audioSessionId, requestId, errorMessage, volume, audioFormat);
    }

    /** 返回错误状态快照，保留队列、位置等其余字段。 */
    public PlaybackState withError(@Nullable String message) {
        return new PlaybackState(queue, currentIndex, songId, Status.ERROR, false, positionMs,
                durationMs, playMode, audioSessionId, requestId, message, volume, audioFormat);
    }

    public List<Song> getQueue() { return queue; }

    /**
     * 生成不可变队列快照。已是 {@link QueueSnapshot} 的列表直接复用，
     * 避免高频进度发布时反复复制整个队列。
     */
    public static List<Song> snapshotQueue(@Nullable List<Song> queue) {
        if (queue == null || queue.isEmpty()) return Collections.emptyList();
        if (queue instanceof QueueSnapshot) return queue;
        return new QueueSnapshot(queue);
    }

    private static final class QueueSnapshot extends java.util.AbstractList<Song>
            implements java.util.RandomAccess {
        private final Song[] items;

        QueueSnapshot(List<Song> source) {
            items = source.toArray(new Song[0]);
        }

        @Override
        public Song get(int index) { return items[index]; }

        @Override
        public int size() { return items.length; }
    }
    public int getCurrentIndex() { return currentIndex; }
    public long getSongId() { return songId; }
    public Status getStatus() { return status; }
    public boolean isPlayWhenReady() { return playWhenReady; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public int getPlayMode() { return playMode; }
    public int getAudioSessionId() { return audioSessionId; }
    public long getRequestId() { return requestId; }
    @Nullable
    public String getErrorMessage() { return errorMessage; }
    /** 用户设置的音量 [0,1]，不包含导航降音。 */
    public float getVolume() { return volume; }
    @Nullable
    public AudioFormat getAudioFormat() { return audioFormat; }

    @Nullable
    public Song getCurrentSong() {
        return currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
    }
}
