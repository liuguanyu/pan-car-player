package com.baidu.carplayer.player;

import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * ExoPlayer 引擎的构建与诊断。音频焦点由 Service 手动管理，因此关闭播放器自带的焦点处理。
 */
public final class PlayerEngine {
    private static final String TAG = "PlayerEngine";
    static final long SEEK_INCREMENT_MS = 5000;

    private PlayerEngine() {
    }

    /** 创建配置好的播放器：解码器回退、扩展渲染器、音乐用途属性、手动焦点。 */
    @OptIn(markerClass = UnstableApi.class)
    public static ExoPlayer create(Context context) {
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        ExoPlayer player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
                .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
                .setAudioAttributes(audioAttributes, false)
                .build();
        Log.d(TAG, "ExoPlayer 初始化完成：解码器回退开启，扩展渲染器 ON，手动音频焦点");
        return player;
    }

    /** 日志用的状态名。 */
    public static String stateName(int playbackState) {
        switch (playbackState) {
            case Player.STATE_IDLE: return "IDLE (空闲)";
            case Player.STATE_BUFFERING: return "BUFFERING (缓冲中)";
            case Player.STATE_READY: return "READY (准备就绪)";
            case Player.STATE_ENDED: return "ENDED (播放结束)";
            default: return "UNKNOWN(" + playbackState + ")";
        }
    }

    /** 就绪时输出音频格式与播放状态，便于排查车机解码问题。 */
    @OptIn(markerClass = UnstableApi.class)
    public static void logReady(ExoPlayer player) {
        Format format = player.getAudioFormat();
        if (format != null) {
            Log.d(TAG, "音频格式: " + format.sampleRate + " Hz, " + format.channelCount + " 声道, "
                    + format.sampleMimeType + ", " + format.bitrate + " bps");
        } else {
            Log.w(TAG, "无法获取音频格式信息");
        }
        Log.d(TAG, "播放状态: " + (player.getPlayWhenReady() ? "播放中" : "已暂停")
                + ", 位置=" + player.getCurrentPosition() + "ms"
                + ", 缓冲=" + player.getBufferedPosition() + "ms"
                + ", 速度=" + player.getPlaybackParameters().speed);
    }
}
