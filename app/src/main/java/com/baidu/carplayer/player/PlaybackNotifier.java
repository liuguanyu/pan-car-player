package com.baidu.carplayer.player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.support.v4.media.session.MediaSessionCompat;

import com.baidu.carplayer.R;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.service.AudioPlayerService;

/**
 * 播放通知：创建渠道、前台通知与更新。
 * MediaStyle 通知是 MediaSession 能接收方向盘/蓝牙按键的前提。
 */
public final class PlaybackNotifier {
    /** 通知按钮动作；由 Service 的 onStartCommand 消费。 */
    public static final String ACTION_PLAY_PAUSE = "com.baidu.carplayer.ACTION_PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.baidu.carplayer.ACTION_NEXT";
    public static final String ACTION_PREVIOUS = "com.baidu.carplayer.ACTION_PREVIOUS";

    private static final String CHANNEL_ID = "audio_player_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_NAME = "音频播放";
    private static final String FALLBACK_TITLE = "音乐播放中";
    private static final String FALLBACK_SUBTITLE = "百度网盘车载播放器";

    private final Context context;

    public PlaybackNotifier(Context context) {
        this.context = context.getApplicationContext();
    }

    public void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("音频播放控制通知");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 构造前台通知。
     * @param session 用于 MediaStyle 关联的会话，可为 null（尚未建立会话时）
     */
    public Notification build(MediaSessionCompat session, Song current, boolean playing) {
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
                new Intent(context, com.baidu.carplayer.MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = current != null && current.getTitle() != null ? current.getTitle() : FALLBACK_TITLE;
        String artist = current != null && current.getArtist() != null ? current.getArtist() : "";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(artist.isEmpty() ? FALLBACK_SUBTITLE : artist)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                // 控制按钮：[上一首][播放/暂停][下一首]
                .addAction(R.drawable.ic_skip_previous, "上一首", action(1, ACTION_PREVIOUS))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "暂停" : "播放", action(2, ACTION_PLAY_PAUSE))
                .addAction(R.drawable.ic_skip_next, "下一首", action(3, ACTION_NEXT));

        if (session != null) {
            builder.setStyle(new MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    /** 提交通知；缺少通知权限时静默跳过。 */
    @SuppressLint("MissingPermission")
    public void notify(MediaSessionCompat session, Song current, boolean playing) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(session, current, playing));
    }

    private PendingIntent action(int requestCode, String action) {
        Intent intent = new Intent(context, AudioPlayerService.class).setAction(action);
        return PendingIntent.getService(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
