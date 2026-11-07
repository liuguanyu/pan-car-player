package com.baidu.carplayer.player;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.playback.AudioFormat;
import com.baidu.carplayer.playback.PlaybackState;

import java.util.List;

import java.util.Locale;

/** 播放页渲染用的派生状态；只由 {@link PlaybackState} 计算，不读取 Service。 */
public final class PlayerViewState {
    public final long songId;
    @Nullable
    public final Song song;
    public final boolean playing;
    public final boolean buffering;
    public final boolean error;
    public final long positionMs;
    public final long durationMs;
    public final int playMode;
    public final int audioSessionId;
    public final int queueSize;
    public final int currentIndex;
    public final long requestId;
    @Nullable
    public final String errorMessage;
    /** 不可变队列快照（与 PlaybackState 共享，不复制）。 */
    public final List<Song> queue;
    public final float volume;
    @Nullable
    public final AudioFormat audioFormat;

    private PlayerViewState(PlaybackState state) {
        song = state.getCurrentSong();
        songId = state.getSongId();
        playing = state.getStatus() == PlaybackState.Status.READY && state.isPlayWhenReady();
        buffering = state.getStatus() == PlaybackState.Status.BUFFERING;
        error = state.getStatus() == PlaybackState.Status.ERROR;
        durationMs = Math.max(0L, state.getDurationMs());
        positionMs = durationMs > 0 ? Math.min(Math.max(0L, state.getPositionMs()), durationMs)
                : Math.max(0L, state.getPositionMs());
        playMode = state.getPlayMode();
        audioSessionId = state.getAudioSessionId();
        queueSize = state.getQueue().size();
        currentIndex = state.getCurrentIndex();
        requestId = state.getRequestId();
        errorMessage = state.getErrorMessage();
        queue = state.getQueue();
        volume = state.getVolume();
        audioFormat = state.getAudioFormat();
    }

    public static PlayerViewState from(PlaybackState state) {
        return new PlayerViewState(state);
    }

    public boolean songChangedFrom(@Nullable PlayerViewState previous) {
        return previous == null || previous.songId != songId;
    }

    /** 音频格式或曲目变化时才需要刷新音频信息行。 */
    public boolean audioInfoChangedFrom(@Nullable PlayerViewState previous) {
        if (previous == null || songChangedFrom(previous) || queueSize != previous.queueSize) return true;
        return audioFormat == null ? previous.audioFormat != null : !audioFormat.equals(previous.audioFormat);
    }

    public boolean playingChangedFrom(@Nullable PlayerViewState previous) {
        return previous == null || previous.playing != playing;
    }

    public static String formatTime(long ms) {
        long safe = Math.max(0L, ms);
        return String.format(Locale.US, "%02d:%02d", safe / 60000, (safe % 60000) / 1000);
    }
}
