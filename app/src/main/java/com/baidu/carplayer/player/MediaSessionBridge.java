package com.baidu.carplayer.player;

import android.content.Context;
import android.content.Intent;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.media.session.MediaButtonReceiver;

import com.baidu.carplayer.playback.PlaybackCommand;

/**
 * 系统媒体会话桥接：把方向盘/蓝牙/系统媒体控制转换为播放命令，
 * 并把播放状态同步给系统。不持有播放器。
 */
public final class MediaSessionBridge {
    /** 会话支持的动作，覆盖方向盘按键对应的全部操作。 */
    static final long SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PAUSE
            | PlaybackStateCompat.ACTION_PLAY_PAUSE
            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            | PlaybackStateCompat.ACTION_STOP
            | PlaybackStateCompat.ACTION_SEEK_TO;

    /** 命令出口：由播放服务实现。 */
    public interface CommandSink {
        void dispatch(PlaybackCommand.Type type);

        /** 拖动进度：需要服务补上当前请求 ID。 */
        void seekTo(long positionMs);
    }

    private final MediaSessionCompat session;

    public MediaSessionBridge(Context context, String tag, CommandSink sink) {
        session = new MediaSessionCompat(context, tag);
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { sink.dispatch(PlaybackCommand.Type.PLAY); }
            @Override public void onPause() { sink.dispatch(PlaybackCommand.Type.PAUSE); }
            @Override public void onSkipToNext() { sink.dispatch(PlaybackCommand.Type.NEXT); }
            @Override public void onSkipToPrevious() { sink.dispatch(PlaybackCommand.Type.PREVIOUS); }
            @Override public void onStop() { sink.dispatch(PlaybackCommand.Type.STOP); }
            @Override public void onSeekTo(long pos) { sink.seekTo(pos); }
        });
        session.setPlaybackState(new PlaybackStateCompat.Builder().setActions(SUPPORTED_ACTIONS).build());
        // API 29+ 上 MediaSessionCompat 会自动接收媒体按键；激活后台播放时也能收到。
        session.setActive(true);
    }

    public MediaSessionCompat session() {
        return session;
    }

    /**
     * 同步系统可见的播放状态。
     * @param hasPlayer 播放器是否存在；不存在时报告 STOPPED
     */
    public void update(boolean hasPlayer, boolean playWhenReady, long positionMs) {
        int state = !hasPlayer ? PlaybackStateCompat.STATE_STOPPED
                : playWhenReady ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(SUPPORTED_ACTIONS)
                .setState(state, hasPlayer ? positionMs : 0, 1.0f)
                .build());
    }

    /**
     * 把 MEDIA_BUTTON Intent 交给会话，使回调生效。
     * @return 是否已处理
     */
    public boolean handleMediaButton(Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return false;
        }
        MediaButtonReceiver.handleIntent(session, intent);
        return true;
    }

    public void release() {
        session.setActive(false);
        session.release();
    }
}
