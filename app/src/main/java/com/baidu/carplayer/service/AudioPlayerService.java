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
import java.util.ArrayList;
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
    
    // 百度网盘认证服务
    private BaiduAuthService authService;
    
    // 持久化相关
    private SharedPreferences sharedPreferences;
    private Gson gson;
    
    // 待处理的seek位置（用于冷启动恢复播放进度）
    private Long pendingSeekPosition = null;
    
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
        
        // 创建ExoPlayer实例
        exoPlayer = new ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build();
        
        Log.d(TAG, "ExoPlayer初始化完成");
        Log.d(TAG, "已启用解码器回退机制");
        Log.d(TAG, "扩展渲染器模式: ON (启用扩展解码器)");
        
        playerListener = new Player.Listener() {
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
     * 设置音频焦点
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
        
        // 设置初始播放状态
        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_STOP);
        mediaSession.setPlaybackState(stateBuilder.build());
        
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
     */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    // 恢复播放
                    if (exoPlayer.getPlayWhenReady()) {
                        exoPlayer.setVolume(1.0f);
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                    // 永久失去焦点，暂停播放
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // 暂时失去焦点，暂停播放
                    pause();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // 暂时失去焦点，降低音量
                    exoPlayer.setVolume(0.3f);
                    break;
            }
        }
    };
    
    /**
     * 请求音频焦点
     */
    private boolean requestAudioFocus() {
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    
    /**
     * 放弃音频焦点
     */
    private void abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
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
            
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "✓ 音频播放已启动");
            Log.d(TAG, "==================================");
        } else {
            Log.e(TAG, "✗ 无法获取音频焦点");
        }
    }
    
    /**
     * 播放（继续播放）
     */
    public void play() {
        if (requestAudioFocus()) {
            exoPlayer.play();
            savePlaybackState(); // 开始播放时保存状态
        }
    }
    
    /**
     * 设置播放列表
     */
    public void setPlaylist(List<com.baidu.carplayer.model.Song> playlist) {
        this.playlist = playlist;
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
        Log.d(TAG, "准备播放歌曲: " + song.getTitle() + ", fsId=" + song.getFsId());
        
        String accessToken = authService.getAccessToken();
        if (accessToken == null) {
            Log.e(TAG, "无法获取访问令牌");
            if (playbackStateListener != null) {
                playbackStateListener.onPlayerError(
                    new PlaybackException("无法获取访问令牌", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
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
                                playbackStateListener.onPlayerError(
                                    new PlaybackException("获取下载链接失败", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
                                );
                            }
                        }
                    } else {
                        Log.e(TAG, "API响应失败: " + response.code());
                        if (playbackStateListener != null) {
                            playbackStateListener.onPlayerError(
                                new PlaybackException("API响应失败", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
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
                // 随机播放模式下，随机选择上一首
                currentPosition = (int) (Math.random() * playlist.size());
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
                // 随机播放模式下，随机选择下一首
                currentPosition = (int) (Math.random() * playlist.size());
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
        savePlaybackState(); // 保存播放状态
    }
    
    /**
     * 设置随机播放模式
     */
    public void setShuffleMode(boolean shuffle) {
        this.playMode = shuffle ? PlayMode.RANDOM : PlayMode.ORDER;
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
    }
    
    /**
     * 继续播放
     */
    public void resume() {
        if (requestAudioFocus()) {
            exoPlayer.play();
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        exoPlayer.stop();
        abandonAudioFocus();
        stopForeground(true);
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
     * 创建通知
     */
    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("正在播放")
                .setContentText("音乐播放中")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true);
        
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
        return START_STICKY;
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
    
    private void startProgressSave() {
        progressSaveHandler.removeCallbacks(progressSaveRunnable);
        progressSaveHandler.postDelayed(progressSaveRunnable, PROGRESS_SAVE_INTERVAL);
    }
    
    private void stopProgressSave() {
        progressSaveHandler.removeCallbacks(progressSaveRunnable);
    }
}