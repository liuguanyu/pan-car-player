package com.baidu.carplayer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.annotation.SuppressLint;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.baidu.carplayer.MainActivity;
import com.baidu.carplayer.R;
import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.lang.reflect.Type;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 音频播放服务 - 使用Media3(ExoPlayer)实现音频播放
 * Media3提供更好的性能和更完善的格式支持，包括ALAC
 */
@OptIn(markerClass = UnstableApi.class)
public class AudioPlayerService extends Service {
    
    private static final String TAG = "AudioPlayerService";
    private static final String CHANNEL_ID = "audio_player_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "AudioPlayerPrefs";
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_CURRENT_POSITION = "current_position";
    private static final String KEY_PLAYBACK_PROGRESS = "playback_progress";
    private static final String KEY_PLAY_MODE = "play_mode";
    private static final String KEY_IS_PLAYING = "is_playing";
    
    private ExoPlayer exoPlayer;
    // 手动管理音频焦点，以正确处理蓝牙导航等场景的焦点抢占
    private AudioFocusRequest audioFocusRequest;
    private AudioManager audioManager;
    private MediaSessionCompat mediaSession;
    private final IBinder binder = new LocalBinder();
    
    private Player.Listener playerListener;
    private PlaybackStateListener playbackStateListener;
    
    // 播放列表相关
    private List<com.baidu.carplayer.model.Song> playlist;
    private int currentPosition = 0;
    private PlayMode playMode = PlayMode.ORDER;
    
    // 随机播放相关
    private List<Integer> shuffledIndices;      // 洗牌后的索引序列
    private int shufflePosition = -1;           // 当前在洗牌序列中的位置
    private SecureRandom secureRandom;          // 密码学安全随机数生成器
    
    // 百度网盘认证服务
    private BaiduAuthService authService;
    
    // 持久化相关
    private SharedPreferences sharedPreferences;
    private Gson gson;
    
    // 待处理的seek位置（用于冷启动恢复播放进度）
    private Long pendingSeekPosition = null;
    
    // 音频焦点相关状态
    private boolean wasPlayingBeforeFocusLoss = false;
    private float volumeBeforeDuck = 1.0f;
    
    // 通知栏控制动作
    private static final String ACTION_PLAY = "com.baidu.carplayer.ACTION_PLAY";
    private static final String ACTION_PAUSE = "com.baidu.carplayer.ACTION_PAUSE";
    private static final String ACTION_PLAY_PAUSE = "com.baidu.carplayer.ACTION_PLAY_PAUSE";
    private static final String ACTION_NEXT = "com.baidu.carplayer.ACTION_NEXT";
    private static final String ACTION_PREVIOUS = "com.baidu.carplayer.ACTION_PREVIOUS";
    
    // 定时保存进度
    private final android.os.Handler progressSaveHandler = new android.os.Handler(Looper.getMainLooper());
    private static final long PROGRESS_SAVE_INTERVAL = 5000; // 每5秒保存一次进度
    
    private final Runnable progressSaveRunnable = new Runnable() {
        @Override
        public void run() {
            if (exoPlayer != null && exoPlayer.getPlayWhenReady() && exoPlayer.getPlaybackState() == Player.STATE_READY) {
                savePlaybackState();
                progressSaveHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL);
            }
        }
    };
    
    /**
     * 播放模式枚举
     */
    public enum PlayMode {
        ORDER,      // 顺序播放
        RANDOM,     // 随机播放
        SINGLE      // 单曲循环
    }
    
    /**
     * 播放状态监听器接口
     */
    public interface PlaybackStateListener {
        void onPlaybackStateChanged(int playbackState);
        void onPlayWhenReadyChanged(boolean playWhenReady);
        void onPlayerError(PlaybackException error);
        void onPositionDiscontinuity();
        default void onAudioSessionIdChanged(int audioSessionId) {}
    }

    public int getAudioSessionId() {
        return exoPlayer != null ? exoPlayer.getAudioSessionId() : 0;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        authService = new BaiduAuthService(this);
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
        initializePlayer();
        createNotificationChannel();
        setupAudioFocus();
        setupMediaSession();
        restorePlaybackState(); // 恢复播放状态
    }
    
    /**
     * 初始化Media3 ExoPlayer
     * Media3相比ExoPlayer 2.x有更好的性能和格式支持
     */
    private void initializePlayer() {
        // 使用DefaultRenderersFactory，Media3会自动选择最佳解码器
        // 设置enableDecoderFallback=true，当硬件解码失败时自动使用软件解码
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        
        // 创建 AudioAttributes
        androidx.media3.common.AudioAttributes audioAttributes =
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build();

        // 创建ExoPlayer实例
        exoPlayer = new ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .setAudioAttributes(audioAttributes, false) // 关闭ExoPlayer自动焦点，由Service手动管理
            .build();
        
        Log.d(TAG, "ExoPlayer初始化完成，已配置AudioAttributes和自动焦点管理");
        Log.d(TAG, "已启用解码器回退机制");
        Log.d(TAG, "扩展渲染器模式: ON (启用扩展解码器)");
        
        playerListener = new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                if (playbackStateListener != null) {
                    playbackStateListener.onAudioSessionIdChanged(audioSessionId);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (playbackStateListener != null) {
                    playbackStateListener.onPlaybackStateChanged(exoPlayer.getPlaybackState());
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                String stateStr = "";
                switch (playbackState) {
                    case Player.STATE_IDLE:
                        stateStr = "IDLE (空闲)";
                        break;
                    case Player.STATE_BUFFERING:
                        stateStr = "BUFFERING (缓冲中)";
                        break;
                    case Player.STATE_READY:
                        stateStr = "READY (准备就绪)";
                        Log.d(TAG, "✓ 播放器准备就绪，可以播放");
                        
                        // 当播放器准备就绪时，输出音频格式信息
                        if (exoPlayer.getAudioFormat() != null) {
                            Log.d(TAG, "========== 音频格式信息 ==========");
                            Log.d(TAG, "采样率: " + exoPlayer.getAudioFormat().sampleRate + " Hz");
                            Log.d(TAG, "声道数: " + exoPlayer.getAudioFormat().channelCount);
                            Log.d(TAG, "编码: " + exoPlayer.getAudioFormat().sampleMimeType);
                            Log.d(TAG, "比特率: " + exoPlayer.getAudioFormat().bitrate + " bps");
                            Log.d(TAG, "================================");
                        } else {
                            Log.w(TAG, "⚠️ 无法获取音频格式信息");
                        }
                        
                        // 处理待处理的seek操作（用于冷启动恢复播放进度）
                        if (pendingSeekPosition != null) {
                            Log.d(TAG, "执行待处理的seek操作: " + pendingSeekPosition + "ms");
                            exoPlayer.seekTo(pendingSeekPosition);
                            exoPlayer.play(); // 确保seek后继续播放
                            pendingSeekPosition = null;
                        }
                        
                        // 检查播放状态
                        Log.d(TAG, "========== 播放状态检查 ==========");
                        Log.d(TAG, "播放状态: " + (exoPlayer.getPlayWhenReady() ? "播放中" : "已暂停"));
                        Log.d(TAG, "当前位置: " + exoPlayer.getCurrentPosition() + " ms");
                        Log.d(TAG, "缓冲位置: " + exoPlayer.getBufferedPosition() + " ms");
                        Log.d(TAG, "播放速度: " + exoPlayer.getPlaybackParameters().speed);
                        Log.d(TAG, "================================");
                        break;
                    case Player.STATE_ENDED:
                        stateStr = "ENDED (播放结束)";
                        break;
                }
                Log.d(TAG, "播放状态变化: " + stateStr);
                
                if (playbackState == Player.STATE_ENDED) {
                    // 播放结束，自动播放下一首，并保存状态
                    savePlaybackState();
                    playNext();
                }

                if (playbackStateListener != null) {
                    playbackStateListener.onPlaybackStateChanged(playbackState);
                }
                updateNotification();
            }
            
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                // 当播放状态改变时保存播放状态（包括播放/暂停状态）
                savePlaybackState();
                Log.d(TAG, "播放状态改变，保存状态: playWhenReady=" + playWhenReady);
                
                if (playWhenReady) {
                    startProgressSave();
                } else {
                    stopProgressSave();
                }
                
                if (playbackStateListener != null) {
                    playbackStateListener.onPlayWhenReadyChanged(playWhenReady);
                }
                updateNotification();
            }
            
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "========== 播放错误 ==========");
                Log.e(TAG, "错误代码: " + error.errorCode);
                Log.e(TAG, "错误信息: " + error.getMessage());
                Log.e(TAG, "错误类型: " + error.getClass().getSimpleName());
                if (error.getCause() != null) {
                    Log.e(TAG, "根本原因: " + error.getCause().getMessage());
                }
                Log.e(TAG, "================================");
                
                if (playbackStateListener != null) {
                    playbackStateListener.onPlayerError(error);
                }
            }
            
            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                               @NonNull Player.PositionInfo newPosition,
                                               int reason) {
                if (playbackStateListener != null) {
                    playbackStateListener.onPositionDiscontinuity();
                }
            }
        };
        
        exoPlayer.addListener(playerListener);
    }
    
    /**
     * 设置音频焦点管理器
     * 手动管理音频焦点以正确处理蓝牙导航等场景的焦点抢占
     */
    private void setupAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
    }
    
    /**
     * 设置MediaSession
     */
    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "AudioPlayerService");
        mediaSession.setCallback(new MediaSessionCallback());
        
        // 设置支持的媒体控制动作（包含所有方向盘按键对应的操作）
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SEEK_TO
                );
        mediaSession.setPlaybackState(stateBuilder.build());
        
        // 设置 MediaButtonReceiver 以使方向盘按键能传递到 MediaSession
        // 这确保了在后台播放时也能接收媒体按键事件
        // 注意：MediaSessionCompat 在 API 29+ 上会自动处理媒体按键
        mediaSession.setActive(true);
    }
    
    /**
     * MediaSession回调
     */
    private class MediaSessionCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            play();
        }
        
        @Override
        public void onPause() {
            pause();
        }
        
        @Override
        public void onSkipToNext() {
            playNext();
        }
        
        @Override
        public void onSkipToPrevious() {
            playPrevious();
        }
        
        @Override
        public void onStop() {
            stop();
        }
    }
    
    /**
     * 音频焦点变化监听器
     * 正确处理蓝牙导航提示音、来电等场景的焦点抢占
     */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        Log.d(TAG, "音频焦点变化: " + focusChange + " (" + getFocusChangeName(focusChange) + ")");
        
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // 焦点恢复（如导航提示音结束、通话结束）
                Log.d(TAG, "✓ 获得音频焦点");
                if (exoPlayer != null) {
                    // 恢复音量（处理蓝牙导航 duck 场景：之前被降到 20%，现在恢复）
                    exoPlayer.setVolume(volumeBeforeDuck);
                    // 恢复播放（处理来电/导航的暂停场景）
                    if (wasPlayingBeforeFocusLoss) {
                        exoPlayer.play();
                        wasPlayingBeforeFocusLoss = false;
                    }
                    updateMediaSessionState();
                    updateNotification();
                }
                break;
                
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // 短暂失去焦点（如来电、导航语音等）
                Log.d(TAG, "⚠ 短暂失去音频焦点（来电/导航），暂停播放");
                if (exoPlayer != null && exoPlayer.getPlayWhenReady()) {
                    wasPlayingBeforeFocusLoss = true;
                    exoPlayer.pause();
                    updateMediaSessionState();
                    updateNotification();
                }
                break;
                
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // 短暂失去焦点但可降低音量（如导航提示音）
                Log.d(TAG, "⚠ 短暂失去音频焦点（可降音），降低音量至20%");
                if (exoPlayer != null && exoPlayer.getPlayWhenReady()) {
                    volumeBeforeDuck = exoPlayer.getVolume();
                    exoPlayer.setVolume(0.2f);
                }
                break;
                
            case AudioManager.AUDIOFOCUS_LOSS:
                // 永久失去焦点（如其他音乐应用开始播放）
                Log.d(TAG, "✗ 永久失去音频焦点，停止播放");
                if (exoPlayer != null) {
                    wasPlayingBeforeFocusLoss = false;
                    exoPlayer.pause();
                    updateMediaSessionState();
                    updateNotification();
                }
                abandonAudioFocus();
                break;
        }
    };
    
    /**
     * 请求音频焦点
     */
    private boolean requestAudioFocus() {
        if (audioManager == null || audioFocusRequest == null) {
            Log.e(TAG, "音频焦点管理器未初始化");
            return false;
        }
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        boolean granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.d(TAG, "请求音频焦点: " + (granted ? "✓ 已获得" : "✗ 被拒绝，result=" + result));
        return granted;
    }
    
    /**
     * 放弃音频焦点
     */
    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            Log.d(TAG, "已放弃音频焦点");
        }
        wasPlayingBeforeFocusLoss = false;
    }
    
    /**
     * 播放音频
     * ExoPlayer 2.19.1对ALAC等格式有更好的支持
     */
    public void play(String url) {
        Log.d(TAG, "========== 开始播放音频 ==========");
        Log.d(TAG, "URL: " + url);
        
        // 检测文件扩展名以识别音频格式
        String fileExtension = "";
        if (url.contains(".")) {
            int lastDotIndex = url.lastIndexOf(".");
            fileExtension = url.substring(lastDotIndex + 1).toLowerCase();
        }
        Log.d(TAG, "检测到音频格式: " + fileExtension);
        
        if (fileExtension.equals("alac") || fileExtension.equals("m4a")) {
            Log.w(TAG, "⚠️ ALAC格式文件");
            Log.w(TAG, "⚠️ 播放取决于设备硬件解码器支持");
            Log.w(TAG, "⚠️ 如果无法播放，建议转换为FLAC或AAC格式");
            Log.w(TAG, "⚠️ 模拟器可能不支持ALAC硬件解码");
        }
        
        // 检查系统音频输出状态
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int musicVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            Log.d(TAG, "系统媒体音量: " + musicVolume + "/" + maxVolume);
            if (musicVolume == 0) {
                Log.e(TAG, "✗ 系统媒体音量为0，无法听到声音！");
            }
            
            // 检查音频输出设备
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.media.AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                Log.d(TAG, "音频输出设备数量: " + devices.length);
                for (android.media.AudioDeviceInfo device : devices) {
                    Log.d(TAG, "  - " + device.getProductName() + " (类型: " + device.getType() + ")");
                }
            }
        }
        
        // 请求音频焦点后再播放
        if (requestAudioFocus()) {
            Log.d(TAG, "✓ 音频焦点获取成功");
            
            MediaItem mediaItem = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(mediaItem);
            
            Log.d(TAG, "准备播放器...");
            
            // 如果有待处理的seek操作，先不自动播放，等待准备好后seek再播放
            if (pendingSeekPosition != null) {
                Log.d(TAG, "有待处理的seek操作，暂停自动播放等待准备完成...");
                exoPlayer.setPlayWhenReady(false);
            } else {
                Log.d(TAG, "开始播放...");
                exoPlayer.setPlayWhenReady(true);
            }
            
            exoPlayer.prepare();
            
            // 检查音量设置
            float volume = exoPlayer.getVolume();
            Log.d(TAG, "播放器音量: " + volume);
            if (volume < 0.5f) {
                Log.w(TAG, "⚠️ 播放器音量较低，可能影响听感");
            }
            
            // 检查设备音量
            // 注意：Media3 ExoPlayer 没有 getDeviceVolume() 方法
            // 如需获取设备音量，应使用 AudioManager
            Log.d(TAG, "设备音量: 通过 AudioManager 已在上方输出");
            
            // 添加播放状态检查
            Log.d(TAG, "========== 播放启动检查 ==========");
            Log.d(TAG, "播放状态: " + (exoPlayer.getPlayWhenReady() ? "播放中" : "已暂停"));
            Log.d(TAG, "播放器状态: " + exoPlayer.getPlaybackState());
            Log.d(TAG, "==================================");
            
            updateMediaSessionState();
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "✓ 音频播放已启动");
            Log.d(TAG, "==================================");
        } else {
            Log.e(TAG, "✗ 无法获取音频焦点，播放被阻止");
        }
    }
    
    /**
     * 播放（继续播放）
     */
    public void play() {
        if (requestAudioFocus()) {
            exoPlayer.play();
            savePlaybackState(); // 开始播放时保存状态
            updateMediaSessionState();
            updateNotification();
        }
    }
    
    /**
     * 设置播放列表
     */
    public void setPlaylist(List<com.baidu.carplayer.model.Song> playlist) {
        this.playlist = playlist;
        // 重置洗牌序列
        shuffledIndices = null;
        shufflePosition = -1;
        savePlaybackState(); // 保存播放状态
    }
    
    /**
     * 在指定位置播放
     */
    public void playAtPosition(int position) {
        playAtPosition(position, true, null);
    }
    
    /**
     * 在指定位置播放
     * @param position 歌曲在列表中的位置
     * @param saveState 是否保存播放状态（用于恢复播放进度时避免覆盖）
     */
    public void playAtPosition(int position, boolean saveState) {
        playAtPosition(position, saveState, null);
    }
    
    /**
     * 在指定位置播放，并在播放器准备好后seek到指定位置
     * @param position 歌曲在列表中的位置
     * @param saveState 是否保存播放状态（用于恢复播放进度时避免覆盖）
     * @param seekToPosition 播放器准备好后要seek到的位置（毫秒），null表示不seek
     */
    public void playAtPosition(int position, boolean saveState, Long seekToPosition) {
        if (playlist == null || position < 0 || position >= playlist.size()) {
            return;
        }
        
        currentPosition = position;
        pendingSeekPosition = seekToPosition;
        Log.d(TAG, "========== playAtPosition ==========");
        Log.d(TAG, "位置: " + position);
        Log.d(TAG, "seekToPosition: " + seekToPosition);
        Log.d(TAG, "pendingSeekPosition已设置: " + pendingSeekPosition);
        com.baidu.carplayer.model.Song song = playlist.get(position);
        playFromBaiduPan(song);
        
        if (saveState) {
            savePlaybackState(); // 保存播放状态
        }
    }
    
    /**
     * 播放指定歌曲
     */
    public void playSong(com.baidu.carplayer.model.Song song) {
        if (playlist != null) {
            // 查找歌曲在播放列表中的位置
            for (int i = 0; i < playlist.size(); i++) {
                if (playlist.get(i).getId() == song.getId()) {
                    currentPosition = i;
                    break;
                }
            }
        }
        playFromBaiduPan(song);
    }
    
    /**
     * 从百度网盘播放歌曲
     */
    private void playFromBaiduPan(com.baidu.carplayer.model.Song song) {
        final String authExpiredMessage = "登录已失效，请重新登录";
        Log.d(TAG, "准备播放歌曲: " + song.getTitle() + ", fsId=" + song.getFsId());
        
        String accessToken = authService.getAccessToken();
        if (accessToken == null) {
            Log.e(TAG, "无法获取访问令牌");
            if (playbackStateListener != null) {
                playbackStateListener.onPlayerError(
                    new PlaybackException(authExpiredMessage, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                );
            }
            return;
        }
        
        // 获取下载链接
        // 注意：百度网盘API要求 fsids 参数为 JSON 数组格式，例如 "[900294067865041]"
        // 必须添加 dlink=1 参数才能获取下载链接
        String fsidsJson = "[" + song.getFsId() + "]";
        RetrofitClient.getPanApiInstance()
            .create(BaiduPanService.class)
            .getFileDownloadLink("filemetas", accessToken, fsidsJson, 1)  // 添加 dlink=1 参数
            .enqueue(new Callback<DownloadLinkResponse>() {
                @Override
                public void onResponse(@NonNull Call<DownloadLinkResponse> call, @NonNull Response<DownloadLinkResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        DownloadLinkResponse downloadResponse = response.body();
                        // 下载链接在 list 数组的第一个元素中
                        if (downloadResponse.getErrno() == 0 && downloadResponse.getList() != null
                                && !downloadResponse.getList().isEmpty()) {
                            String downloadUrl = downloadResponse.getList().get(0).getDlink();
                            Log.d(TAG, "获取下载链接成功: " + downloadUrl);
                            
                            // 百度网盘下载链接需要附加 access_token 参数
                            String urlWithToken = downloadUrl + (downloadUrl.contains("?") ? "&" : "?") + "access_token=" + accessToken;
                            Log.d(TAG, "带token的下载链接: " + urlWithToken);
                            
                            // 使用带token的下载链接播放
                            play(urlWithToken);
                        } else {
                            Log.e(TAG, "获取下载链接失败，errno=" + downloadResponse.getErrno());
                            if (playbackStateListener != null) {
                                String message = isAuthError(downloadResponse.getErrno())
                                        ? authExpiredMessage : "获取下载链接失败";
                                playbackStateListener.onPlayerError(
                                    new PlaybackException(message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                                );
                            }
                        }
                    } else {
                        Log.e(TAG, "API响应失败: " + response.code());
                        if (playbackStateListener != null) {
                            String message = (response.code() == 401 || response.code() == 403)
                                    ? authExpiredMessage : "API响应失败";
                            playbackStateListener.onPlayerError(
                                new PlaybackException(message, null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                            );
                        }
                    }
                }
                
                @Override
                public void onFailure(@NonNull Call<DownloadLinkResponse> call, @NonNull Throwable t) {
                    Log.e(TAG, "获取下载链接网络请求失败", t);
                    if (playbackStateListener != null) {
                        playbackStateListener.onPlayerError(
                            new PlaybackException("网络请求失败: " + t.getMessage(), t, PlaybackException.ERROR_CODE_UNSPECIFIED)
                        );
                    }
                }
            });
    }
    
    /** 百度网盘常见的 token 失效错误码。 */
    private boolean isAuthError(int errno) {
        return errno == 110 || errno == 111 || errno == 31034;
    }

    /**
     * 检查播放器是否实际加载了媒体项
     * @return true如果播放器有媒体项，false如果播放器为空
     */
    public boolean isPlayerReady() {
        if (exoPlayer == null) {
            return false;
        }
        // 检查播放器是否有媒体项
        boolean hasMedia = exoPlayer.getMediaItemCount() > 0;
        Log.d(TAG, "isPlayerReady: mediaItemCount=" + exoPlayer.getMediaItemCount() + ", hasMedia=" + hasMedia);
        return hasMedia;
    }
    
    /**
     * 获取当前歌曲
     */
    public com.baidu.carplayer.model.Song getCurrentSong() {
        if (playlist != null && currentPosition >= 0 && currentPosition < playlist.size()) {
            return playlist.get(currentPosition);
        }
        return null;
    }
    
    /**
     * 上一曲
     */
    public void playPrevious() {
        if (playlist == null || playlist.isEmpty()) {
            return;
        }
        
        switch (playMode) {
            case RANDOM:
                if (shuffledIndices == null || shuffledIndices.isEmpty()) {
                    generateShuffleOrder();
                }
                if (shufflePosition > 0) {
                    shufflePosition--;
                    currentPosition = shuffledIndices.get(shufflePosition);
                }
                // 如果已经是洗牌序列的第一首，则不变
                break;
            case SINGLE:
                // 单曲循环模式下，不切换
                break;
            case ORDER:
            default:
                // 顺序播放模式下，播放上一首
                currentPosition--;
                if (currentPosition < 0) {
                    currentPosition = playlist.size() - 1; // 循环到最后一首
                }
                break;
        }
        
        playAtPosition(currentPosition);
    }
    
    /**
     * 下一曲
     */
    public void playNext() {
        if (playlist == null || playlist.isEmpty()) {
            return;
        }
        
        switch (playMode) {
            case RANDOM:
                if (shuffledIndices == null || shuffledIndices.isEmpty() || shuffledIndices.size() != playlist.size()) {
                    generateShuffleOrder();
                }
                shufflePosition++;
                if (shufflePosition >= shuffledIndices.size()) {
                    // 播完一轮，重新洗牌，但避免首尾衔接重复
                    int lastPlayed = shuffledIndices.get(shuffledIndices.size() - 1);
                    generateShuffleOrder();
                    if (shuffledIndices.size() > 1 && shuffledIndices.get(0) == lastPlayed) {
                        int swapPos = 1 + secureRandom.nextInt(shuffledIndices.size() - 1);
                        Collections.swap(shuffledIndices, 0, swapPos);
                    }
                    shufflePosition = 0;
                }
                currentPosition = shuffledIndices.get(shufflePosition);
                break;
            case SINGLE:
                // 单曲循环模式下，不切换
                break;
            case ORDER:
            default:
                // 顺序播放模式下，播放下一首
                currentPosition++;
                if (currentPosition >= playlist.size()) {
                    currentPosition = 0; // 循环到第一首
                }
                break;
        }
        
        playAtPosition(currentPosition);
    }
    
    /**
     * 获取播放模式
     */
    public PlayMode getPlayMode() {
        return playMode;
    }
    
    /**
     * 设置播放模式
     */
    public void setPlayMode(PlayMode playMode) {
        this.playMode = playMode;
        if (playMode == PlayMode.RANDOM) {
            generateShuffleOrder();
        }
        savePlaybackState(); // 保存播放状态
    }
    
    /**
     * 设置随机播放模式
     */
    public void setShuffleMode(boolean shuffle) {
        this.playMode = shuffle ? PlayMode.RANDOM : PlayMode.ORDER;
        if (shuffle) {
            generateShuffleOrder();
        }
    }
    
    /**
     * 使用 Fisher-Yates 洗牌算法 + SecureRandom 生成随机播放序列
     * 确保每首歌在一个周期内恰好播放一次
     */
    private void generateShuffleOrder() {
        if (playlist == null || playlist.isEmpty()) return;
        
        int size = playlist.size();
        shuffledIndices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            shuffledIndices.add(i);
        }
        
        if (secureRandom == null) {
            secureRandom = new SecureRandom();
        }
        
        // Fisher-Yates 洗牌
        for (int i = size - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            Collections.swap(shuffledIndices, i, j);
        }
        
        // 将当前正在播放的歌曲放到洗牌序列的第一个位置
        if (currentPosition >= 0 && currentPosition < size) {
            int currentIndex = shuffledIndices.indexOf(currentPosition);
            if (currentIndex > 0) {
                Collections.swap(shuffledIndices, 0, currentIndex);
            }
        }
        shufflePosition = 0;
        
        Log.d(TAG, "Generated shuffle order, size=" + size + ", shufflePosition=0");
    }
    
    /**
     * 获取音量
     */
    public float getVolume() {
        return exoPlayer.getVolume();
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        exoPlayer.pause();
        savePlaybackState(); // 暂停时保存状态
        updateMediaSessionState();
    }
    
    /**
     * 继续播放
     */
    public void resume() {
        if (requestAudioFocus()) {
            exoPlayer.play();
            updateMediaSessionState();
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        exoPlayer.stop();
        abandonAudioFocus();
        stopForeground(true);
        updateMediaSessionState();
    }
    
    /**
     * 上一曲
     */
    public void previous() {
        exoPlayer.seekToPrevious();
    }
    
    /**
     * 下一曲
     */
    public void next() {
        exoPlayer.seekToNext();
    }
    
    /**
     * 跳转到指定位置
     */
    public void seekTo(long positionMs) {
        exoPlayer.seekTo(positionMs);
    }
    
    /**
     * 设置音量
     */
    public void setVolume(float volume) {
        exoPlayer.setVolume(volume);
    }
    
    /**
     * 获取当前播放位置
     */
    public long getCurrentPosition() {
        return exoPlayer.getCurrentPosition();
    }
    
    /**
     * 获取总时长
     */
    public long getDuration() {
        return exoPlayer.getDuration();
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return exoPlayer.getPlayWhenReady() && exoPlayer.getPlaybackState() == Player.STATE_READY;
    }
    
    /**
     * 设置播放状态监听器
     */
    public void setPlaybackStateListener(PlaybackStateListener listener) {
        this.playbackStateListener = listener;
    }
    
    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "音频播放",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("音频播放控制通知");
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
    
    /**
     * 创建 MediaStyle 通知（含播放控制按钮）
     * MediaStyle 通知是 MediaSession 正确接收媒体按键的前提条件
     */
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // 创建通知栏控制按钮的 PendingIntent（通过 Service 的 onStartCommand 处理）
        // 上一首
        Intent prevIntent = new Intent(this, AudioPlayerService.class);
        prevIntent.setAction(ACTION_PREVIOUS);
        PendingIntent prevPendingIntent = PendingIntent.getService(
                this, 1, prevIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        // 播放/暂停
        Intent playPauseIntent = new Intent(this, AudioPlayerService.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePendingIntent = PendingIntent.getService(
                this, 2, playPauseIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        // 下一首
        Intent nextIntent = new Intent(this, AudioPlayerService.class);
        nextIntent.setAction(ACTION_NEXT);
        PendingIntent nextPendingIntent = PendingIntent.getService(
                this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        // 获取当前歌曲信息用于显示
        String title = "音乐播放中";
        String subtitle = "";
        if (playlist != null && currentPosition >= 0 && currentPosition < playlist.size()) {
            com.baidu.carplayer.model.Song song = playlist.get(currentPosition);
            title = song.getTitle() != null ? song.getTitle() : "未知歌曲";
            subtitle = song.getArtist() != null ? song.getArtist() : "";
        }
        
        boolean playing = isPlaying();
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle.isEmpty() ? "百度网盘车载播放器" : subtitle)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                // 使用 MediaStyle 通知，这是 MediaSession 工作的核心要求
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)) // 在紧凑视图中显示三个按钮
                // 添加控制按钮：[上一首][播放/暂停][下一首]
                .addAction(R.drawable.ic_skip_previous, "上一首", prevPendingIntent)
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                           playing ? "暂停" : "播放", playPausePendingIntent)
                .addAction(R.drawable.ic_skip_next, "下一首", nextPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        
        return builder.build();
    }
    
    /**
     * 更新通知
     */
    @SuppressLint("MissingPermission")
    private void updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        
        // 同步更新 MediaSession 状态
        updateMediaSessionState();
        
        NotificationManagerCompat manager = NotificationManagerCompat.from(this);
        manager.notify(NOTIFICATION_ID, createNotification());
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 处理通知栏和媒体按键的控制动作
        if (intent != null && intent.getAction() != null) {
            handleMediaAction(intent.getAction());
        }
        return START_STICKY;
    }
    
    /**
     * 处理媒体控制动作（通知栏按钮、蓝牙按键等）
     */
    private void handleMediaAction(String action) {
        if (action == null) return;
        Log.d(TAG, "处理媒体动作: " + action);
        switch (action) {
            case ACTION_PLAY:
                play();
                break;
            case ACTION_PAUSE:
                pause();
                break;
            case ACTION_PLAY_PAUSE:
                if (isPlaying()) {
                    pause();
                } else {
                    play();
                }
                break;
            case ACTION_NEXT:
                playNext();
                break;
            case ACTION_PREVIOUS:
                playPrevious();
                break;
            default:
                Log.w(TAG, "未知媒体动作: " + action);
                break;
        }
    }
    
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 当系统内存不足时保存播放状态
        savePlaybackState();
        Log.d(TAG, "onTrimMemory: 系统内存不足，保存播放状态，level=" + level);
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // 当用户从最近任务列表清除应用时保存播放状态
        savePlaybackState();
        Log.d(TAG, "onTaskRemoved: 应用从最近任务列表被清除，保存播放状态");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProgressSave();
        savePlaybackState(); // 在服务销毁前保存状态
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
    }
    
    /**
     * 保存播放状态到 SharedPreferences
     */
    public void savePlaybackState() {
        if (playlist == null || playlist.isEmpty()) {
            return;
        }
        
        SharedPreferences.Editor editor = sharedPreferences.edit();
        
        // 保存播放列表（序列化为 JSON）
        String playlistJson = gson.toJson(playlist);
        editor.putString(KEY_PLAYLIST, playlistJson);
        
        // 保存当前播放位置（歌曲在列表中的索引）
        editor.putInt(KEY_CURRENT_POSITION, currentPosition);
        
        // 保存当前播放进度（毫秒）
        if (exoPlayer != null) {
            editor.putLong(KEY_PLAYBACK_PROGRESS, exoPlayer.getCurrentPosition());
        }
        
        // 保存播放模式
        editor.putString(KEY_PLAY_MODE, playMode.name());
        
        // 保存播放状态（是否正在播放）
        boolean isPlaying = exoPlayer != null && exoPlayer.getPlayWhenReady();
        editor.putBoolean(KEY_IS_PLAYING, isPlaying);
        
        editor.apply();
        Log.d(TAG, "播放状态已保存: position=" + currentPosition + ", mode=" + playMode + ", isPlaying=" + isPlaying);
    }
    
    /**
     * 从 SharedPreferences 恢复播放状态
     */
    private void restorePlaybackState() {
        // 恢复播放列表
        String playlistJson = sharedPreferences.getString(KEY_PLAYLIST, null);
        if (playlistJson != null) {
            Type listType = new TypeToken<ArrayList<com.baidu.carplayer.model.Song>>(){}.getType();
            playlist = gson.fromJson(playlistJson, listType);
            
            // 恢复当前播放位置
            currentPosition = sharedPreferences.getInt(KEY_CURRENT_POSITION, 0);
            
            // 恢复播放模式
            String playModeName = sharedPreferences.getString(KEY_PLAY_MODE, PlayMode.ORDER.name());
            try {
                playMode = PlayMode.valueOf(playModeName);
            } catch (IllegalArgumentException e) {
                playMode = PlayMode.ORDER;
            }
            
            // 如果恢复的是随机模式，重新生成洗牌序列
            if (playMode == PlayMode.RANDOM) {
                generateShuffleOrder();
            }
            
            Log.d(TAG, "播放状态已恢复: playlist size=" + (playlist != null ? playlist.size() : 0)
                    + ", position=" + currentPosition + ", mode=" + playMode);
        } else {
            Log.d(TAG, "没有保存的播放状态");
        }
    }
    
    /**
     * 检查是否有保存的播放状态
     */
    public boolean hasSavedPlaybackState() {
        return sharedPreferences.contains(KEY_PLAYLIST);
    }
    
    /**
     * 获取保存的播放列表
     */
    public List<com.baidu.carplayer.model.Song> getSavedPlaylist() {
        return playlist;
    }
    
    /**
     * 获取保存的播放位置
     */
    public int getSavedPosition() {
        return currentPosition;
    }

    /**
     * 获取保存的播放进度（毫秒）
     */
    public long getSavedProgress() {
        return sharedPreferences.getLong(KEY_PLAYBACK_PROGRESS, 0);
    }
    
    /**
     * LocalBinder - 提供对Service实例的访问
     */
    public class LocalBinder extends Binder {
        public AudioPlayerService getService() {
            return AudioPlayerService.this;
        }
    }
    
    /**
     * 更新 MediaSession 播放状态
     * 当播放/暂停状态变化时调用，确保通知栏和系统UI反映最新状态
     */
    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_STOP |
                        PlaybackStateCompat.ACTION_SEEK_TO
                );
        
        if (exoPlayer != null) {
            int state = exoPlayer.getPlayWhenReady() 
                    ? PlaybackStateCompat.STATE_PLAYING 
                    : PlaybackStateCompat.STATE_PAUSED;
            stateBuilder.setState(state, exoPlayer.getCurrentPosition(), 1.0f);
        } else {
            stateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, 0, 1.0f);
        }
        
        mediaSession.setPlaybackState(stateBuilder.build());
    }
    
    /**
     * 获取音频焦点变化原因的友好名称（用于日志）
     */
    private String getFocusChangeName(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:                     return "获得焦点";
            case AudioManager.AUDIOFOCUS_LOSS:                    return "永久失去";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:          return "短暂失去(暂停)";
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: return "短暂失去(可降音)";
            default: return "未知(" + focusChange + ")";
        }
    }
    
    private void startProgressSave() {
        progressSaveHandler.removeCallbacks(progressSaveRunnable);
        progressSaveHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL);
    }
    
    private void stopProgressSave() {
        progressSaveHandler.removeCallbacks(progressSaveRunnable);
    }
    
    /**
     * 音频格式信息类
     */
    public static class AudioFormatInfo {
        public String sampleMimeType;  // 编码格式
        public int sampleRate;         // 采样率
        public long bitrate;           // 比特率
        public int channelCount;       // 声道数
        
        public AudioFormatInfo(String sampleMimeType, int sampleRate, long bitrate, int channelCount) {
            this.sampleMimeType = sampleMimeType;
            this.sampleRate = sampleRate;
            this.bitrate = bitrate;
            this.channelCount = channelCount;
        }
    }
    
    /**
     * 获取当前播放的音频格式信息
     * @return AudioFormatInfo 对象，如果无法获取则返回 null
     */
    @Nullable
    public AudioFormatInfo getAudioFormatInfo() {
        if (exoPlayer == null || exoPlayer.getAudioFormat() == null) {
            return null;
        }
        
        androidx.media3.common.Format format = exoPlayer.getAudioFormat();
        return new AudioFormatInfo(
            format.sampleMimeType,
            format.sampleRate,
            format.bitrate,
            format.channelCount
        );
    }
}