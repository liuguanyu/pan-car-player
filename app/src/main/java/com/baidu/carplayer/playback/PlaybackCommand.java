package com.baidu.carplayer.playback;

import androidx.annotation.Nullable;

/** 播放控制意图；seek 和 autoplay 与具体请求绑定。 */
public final class PlaybackCommand {
    public enum Type { PLAY, PAUSE, TOGGLE, SEEK, SELECT, NEXT, PREVIOUS, STOP,
        SET_PLAY_MODE, SET_VOLUME, SAVE_STATE }

    private final Type type;
    private final long requestId;
    private final long songId;
    private final boolean hasSeekPosition;
    private final long seekPositionMs;
    private final boolean playWhenReady;
    private final int playMode;
    private final float volume;

    private PlaybackCommand(Type type, long requestId, long songId, boolean hasSeekPosition,
                            long seekPositionMs, boolean playWhenReady) {
        this(type, requestId, songId, hasSeekPosition, seekPositionMs, playWhenReady, -1, 1f);
    }

    private PlaybackCommand(Type type, long requestId, long songId, boolean hasSeekPosition,
                            long seekPositionMs, boolean playWhenReady, int playMode, float volume) {
        this.type = type;
        this.requestId = requestId;
        this.songId = songId;
        this.hasSeekPosition = hasSeekPosition;
        this.seekPositionMs = seekPositionMs;
        this.playWhenReady = playWhenReady;
        this.playMode = playMode;
        this.volume = volume;
    }

    public static PlaybackCommand select(long requestId, long songId,
                                         @Nullable Long seekPositionMs, boolean playWhenReady) {
        return new PlaybackCommand(Type.SELECT, requestId, songId, seekPositionMs != null,
                seekPositionMs == null ? 0L : seekPositionMs, playWhenReady);
    }

    public static PlaybackCommand seek(long requestId, long positionMs) {
        return new PlaybackCommand(Type.SEEK, requestId, -1L, true, Math.max(0L, positionMs), false);
    }

    /** 设置播放模式（取值为 {@code AudioPlayerService.PlayMode} 的序号）。 */
    public static PlaybackCommand setPlayMode(int playModeOrdinal) {
        return new PlaybackCommand(Type.SET_PLAY_MODE, 0L, -1L, false, 0L, false,
                Math.max(0, playModeOrdinal), 1f);
    }

    /** 设置用户音量，范围 [0, 1]；导航降音是 Service 内部状态，不受它影响。 */
    public static PlaybackCommand setVolume(float volume) {
        float clamped = Float.isNaN(volume) ? 1f : Math.max(0f, Math.min(1f, volume));
        return new PlaybackCommand(Type.SET_VOLUME, 0L, -1L, false, 0L, false, -1, clamped);
    }

    public static PlaybackCommand simple(Type type, long requestId) {
        return new PlaybackCommand(type, requestId, -1L, false, 0L, type == Type.PLAY);
    }

    /** 返回只修改 autoplay 意图的新命令，例如加载期间用户按下暂停。 */
    public PlaybackCommand withPlayWhenReady(boolean value) {
        return new PlaybackCommand(type, requestId, songId, hasSeekPosition, seekPositionMs, value);
    }

    /** seek 已执行后返回不再携带 seek 的命令，避免重复消费。 */
    public PlaybackCommand withoutSeek() {
        return new PlaybackCommand(type, requestId, songId, false, 0L, playWhenReady);
    }

    public Type getType() { return type; }
    public long getRequestId() { return requestId; }
    public long getSongId() { return songId; }
    public boolean hasSeekPosition() { return hasSeekPosition; }
    public long getSeekPositionMs() { return seekPositionMs; }
    public boolean isPlayWhenReady() { return playWhenReady; }
    public int getPlayMode() { return playMode; }
    public float getVolume() { return volume; }
}
