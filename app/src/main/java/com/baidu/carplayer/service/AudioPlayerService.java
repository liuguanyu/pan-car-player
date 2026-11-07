package com.baidu.carplayer.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;
import com.baidu.carplayer.playback.AudioFormat;
import com.baidu.carplayer.playback.PlaybackCommand;
import com.baidu.carplayer.player.FocusPolicy;
import com.baidu.carplayer.player.MediaSessionBridge;
import com.baidu.carplayer.player.PlaybackQueue;
import com.baidu.carplayer.player.PlayerEngine;
import com.baidu.carplayer.player.PlaybackNotifier;
import com.baidu.carplayer.player.PlaybackPersistence;
import com.baidu.carplayer.playback.PlaybackCoordinator;
import com.baidu.carplayer.playback.PlaybackRequestTracker;
import com.baidu.carplayer.playback.PlaybackState;
import com.baidu.carplayer.network.LogRedactor;
import com.baidu.carplayer.repository.BaiduDownloadUrlRepository;
import com.baidu.carplayer.repository.DownloadUrlRepository;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.List;


/**
 * 音频播放服务 - 使用Media3(ExoPlayer)实现音频播放
 * Media3提供更好的性能和更完善的格式支持，包括ALAC
 */
@OptIn(markerClass = UnstableApi.class)
public class AudioPlayerService extends Service {
    
    private static final String TAG = "AudioPlayerService";
    private static final int NOTIFICATION_ID = 1;
    
    private ExoPlayer exoPlayer;
    // 手动管理音频焦点，以正确处理蓝牙导航等场景的焦点抢占
    private AudioFocusRequest audioFocusRequest;
    private AudioManager audioManager;
    private MediaSessionCompat mediaSession;
    private MediaSessionBridge mediaSessionBridge;
    private final IBinder binder = new LocalBinder();
    
    private Player.Listener playerListener;
    // 使用多订阅集合，避免不同 Activity 互相覆盖播放状态监听器。
    
    // 播放列表相关
    /** 队列与导航（下标、随机序列）由 PlaybackQueue 持有。 */
    private final PlaybackQueue queue = new PlaybackQueue();
    
    // 随机播放相关

    
    // 百度网盘认证服务
    private BaiduAuthService authService;
    
    // 持久化相关
    private PlaybackPersistence persistence;
    private PlaybackNotifier notifier;

    // 加载请求代次与 seek/autoplay 意图；网络回调只能提交当前请求的结果。
    private final PlaybackRequestTracker requestTracker = new PlaybackRequestTracker();
    private final PlaybackCoordinator playbackCoordinator = new PlaybackCoordinator(
            new PlaybackState(null, -1, -1L, PlaybackState.Status.IDLE, false, 0L, 0L, 0, 0, 0L));
    private DownloadUrlRepository downloadUrlRepository;
    @Nullable
    private DownloadUrlRepository.Cancellable pendingUrlRequest;
    private boolean destroyed = false;
    // 最近一次加载/解码失败；之后的 PLAY 需要重新获取下载地址，而不是恢复旧媒体项。
    private boolean loadFailed = false;
    
    // 音频焦点相关状态
    private boolean wasPlayingBeforeFocusLoss = false;
    // 用户音量与导航降音分开记录：降音期间调整音量不会被焦点恢复覆盖。
    private float userVolume = 1.0f;
    private boolean ducked = false;
    
    // 通知栏控制动作
    private static final String ACTION_PLAY = "com.baidu.carplayer.ACTION_PLAY";
    private static final String ACTION_PAUSE = "com.baidu.carplayer.ACTION_PAUSE";
    private static final String ACTION_PLAY_PAUSE = PlaybackNotifier.ACTION_PLAY_PAUSE;
    private static final String ACTION_NEXT = PlaybackNotifier.ACTION_NEXT;
    private static final String ACTION_PREVIOUS = PlaybackNotifier.ACTION_PREVIOUS;
    
    // 定时保存进度
    private final android.os.Handler commandHandler = new android.os.Handler(Looper.getMainLooper());
    private final android.os.Handler progressSaveHandler = new android.os.Handler(Looper.getMainLooper());
    private static final long PROGRESS_SAVE_INTERVAL = 5000; // 每5秒保存一次进度
    
    // 播放中按固定间隔发布进度快照，页面不再自行轮询 Service。
    private static final long PROGRESS_PUBLISH_INTERVAL = 500;
    private final Runnable progressPublishRunnable = new Runnable() {
        @Override
        public void run() {
            publishPlaybackState();
            if (exoPlayer != null && exoPlayer.isPlaying()) {
                commandHandler.postDelayed(this, PROGRESS_PUBLISH_INTERVAL);
            }
        }
    };

    private void startProgressPublish() {
        commandHandler.removeCallbacks(progressPublishRunnable);
        commandHandler.post(progressPublishRunnable);
    }

    private void stopProgressPublish() {
        commandHandler.removeCallbacks(progressPublishRunnable);
    }

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
    /** 播放模式；与 {@link PlaybackQueue.Mode} 同序，可直接按 ordinal 互转。 */
    public enum PlayMode {
        ORDER,      // 顺序播放
        RANDOM,     // 随机播放
        SINGLE;     // 单曲循环

        /** 未知或缺失的名字回落到 ORDER（例如旧版本写入的值）。 */
        static PlaybackQueue.Mode fromName(String name) {
            if (name == null) return PlaybackQueue.Mode.ORDER;
            try {
                return PlaybackQueue.Mode.valueOf(name);
            } catch (IllegalArgumentException e) {
                return PlaybackQueue.Mode.ORDER;
            }
        }
    }
    

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        authService = BaiduAuthService.getInstance(this);
        if (downloadUrlRepository == null) {
            downloadUrlRepository = new BaiduDownloadUrlRepository(
                    RetrofitClient.getPanApiInstance().create(BaiduPanService.class), authService);
        }
        persistence = PlaybackPersistence.from(this);
        initializePlayer();
        notifier = new PlaybackNotifier(this);
        notifier.createChannel();
        setupAudioFocus();
        setupMediaSession();
        restorePlaybackState(); // 恢复播放状态
    }
    
    /**
     * 初始化Media3 ExoPlayer
     * Media3相比ExoPlayer 2.x有更好的性能和格式支持
     */
    private void initializePlayer() {
        exoPlayer = PlayerEngine.create(this);
        
        playerListener = new Player.Listener() {
            @Override
            public void onAudioSessionIdChanged(int audioSessionId) {
                publishPlaybackState();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                publishPlaybackState();
                if (isPlaying) {
                    startProgressPublish();
                } else {
                    stopProgressPublish();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "播放状态变化: " + PlayerEngine.stateName(playbackState));
                if (playbackState == Player.STATE_READY) {
                    PlayerEngine.logReady(exoPlayer);
                    // 只消费与当前加载请求绑定的 seek/autoplay 意图。
                    PlaybackCommand command = requestTracker.consumeSeek();
                    if (command != null) {
                        Log.d(TAG, "执行待处理的seek操作: " + command.getSeekPositionMs() + "ms");
                        exoPlayer.seekTo(command.getSeekPositionMs());
                        exoPlayer.setPlayWhenReady(command.isPlayWhenReady());
                    }
                }
                
                if (playbackState == Player.STATE_ENDED) {
                    // 播放结束，自动播放下一首，并保存状态
                    savePlaybackState();
                    playNext();
                }

                publishPlaybackState();
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
                
                publishPlaybackState();
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
                
                notifyPlayerError(error);
            }
            
            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                               @NonNull Player.PositionInfo newPosition,
                                               int reason) {
                publishPlaybackState();
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
    
    /** 建立系统媒体会话；方向盘/蓝牙/系统控件都通过它转换为播放命令。 */
    private void setupMediaSession() {
        mediaSessionBridge = new MediaSessionBridge(this, "AudioPlayerService",
                new MediaSessionBridge.CommandSink() {
                    @Override
                    public void dispatch(PlaybackCommand.Type type) {
                        AudioPlayerService.this.dispatch(type);
                    }

                    @Override
                    public void seekTo(long positionMs) {
                        dispatchCommand(PlaybackCommand.seek(requestTracker.getGeneration(), positionMs));
                    }
                });
        mediaSession = mediaSessionBridge.session();
    }
    
    /**
     * 音频焦点变化监听器
     * 正确处理蓝牙导航提示音、来电等场景的焦点抢占
     */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = focusChange -> {
        boolean playWhenReady = exoPlayer != null && exoPlayer.getPlayWhenReady();
        FocusPolicy.Action action = FocusPolicy.forFocusChange(focusChange, playWhenReady);
        Log.d(TAG, "音频焦点变化: " + focusChange + " (" + getFocusChangeName(focusChange) + ") -> " + action);

        switch (action) {
            case RESTORE:
                // 焦点恢复（导航提示音结束、通话结束）：恢复用户音量，必要时继续播放。
                ducked = false;
                if (exoPlayer != null) {
                    exoPlayer.setVolume(userVolume);
                    if (wasPlayingBeforeFocusLoss) {
                        exoPlayer.play();
                        wasPlayingBeforeFocusLoss = false;
                    }
                    updateMediaSessionState();
                    updateNotification();
                }
                break;

            case PAUSE_TRANSIENT:
                wasPlayingBeforeFocusLoss = true;
                exoPlayer.pause();
                updateMediaSessionState();
                updateNotification();
                break;

            case DUCK:
                ducked = true;
                exoPlayer.setVolume(FocusPolicy.duckedVolume(userVolume));
                break;

            case STOP:
                wasPlayingBeforeFocusLoss = false;
                if (exoPlayer != null) {
                    exoPlayer.pause();
                    updateMediaSessionState();
                    updateNotification();
                }
                abandonAudioFocus();
                break;

            case NONE:
            default:
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
    private void play(String url) {
        if (destroyed || exoPlayer == null) {
            Log.w(TAG, "播放器已销毁，忽略播放请求");
            return;
        }
        Log.d(TAG, "========== 开始播放音频 ==========");
        Log.d(TAG, "URL: " + LogRedactor.redact(url));
        
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
            
            loadFailed = false;
            MediaItem mediaItem = MediaItem.fromUri(url);
            exoPlayer.setMediaItem(mediaItem);
            
            Log.d(TAG, "准备播放器...");
            
            // 恢复命令的 seek 和 autoplay 意图由同一 generation 绑定。
            PlaybackCommand command = requestTracker.current();
            if (command != null && command.hasSeekPosition()) {
                Log.d(TAG, "有待处理的seek操作，等待准备完成...");
                exoPlayer.setPlayWhenReady(false);
            } else {
                boolean autoplay = command == null || command.isPlayWhenReady();
                Log.d(TAG, autoplay ? "开始播放..." : "加载完成后保持暂停");
                exoPlayer.setPlayWhenReady(autoplay);
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
            startForeground(NOTIFICATION_ID, notifier.build(mediaSession, queue.current(), isPlaying()));
            Log.d(TAG, "✓ 音频播放已启动");
            Log.d(TAG, "==================================");
        } else {
            Log.e(TAG, "✗ 无法获取音频焦点，播放被阻止");
        }
    }
    
    /**
     * 播放（继续播放）
     */
    private void play() {
        if (destroyed || exoPlayer == null) {
            return;
        }
        if (requestTracker.current() == null && needsReload() && getCurrentSong() != null) {
            Log.d(TAG, "播放器无可用媒体，重新加载当前歌曲");
            playAtPosition(queue.getIndex(), true, null);
            return;
        }
        requestTracker.setPlayWhenReady(true);
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
    private void setPlaylist(List<com.baidu.carplayer.model.Song> songs) {
        queue.setSongs(songs);
        savePlaybackState(); // 保存播放状态
        publishPlaybackState();
    }
    
    /**
     * 在指定位置播放
     */
    private void playAtPosition(int position) {
        playAtPosition(position, true, null);
    }
    
    /**
     * 在指定位置播放
     * @param position 歌曲在列表中的位置
     * @param saveState 是否保存播放状态（用于恢复播放进度时避免覆盖）
     */
    private void playAtPosition(int position, boolean saveState) {
        playAtPosition(position, saveState, null);
    }
    
    /**
     * 在指定位置播放，并在播放器准备好后seek到指定位置
     * @param position 歌曲在列表中的位置
     * @param saveState 是否保存播放状态（用于恢复播放进度时避免覆盖）
     * @param seekToPosition 播放器准备好后要seek到的位置（毫秒），null表示不seek
     */
    private void playAtPosition(int position, boolean saveState, Long seekToPosition) {
        if (!queue.moveTo(position)) {
            return;
        }
        com.baidu.carplayer.model.Song song = queue.current();
        final long requestGeneration = requestTracker.begin(song.getId(), seekToPosition, true).getRequestId();
        // 切歌后立即停掉旧歌：否则在新地址返回前（弱网/断网时可能很久）
        // 界面已显示新歌而扬声器仍在播旧歌，用户会以为切歌无效、顺序错乱。
        stopCurrentMedia();
        Log.d(TAG, "========== playAtPosition ==========");
        Log.d(TAG, "位置: " + position);
        Log.d(TAG, "seekToPosition: " + seekToPosition);
        publishPlaybackState();
        playFromBaiduPan(song, requestGeneration);
        
        if (saveState) {
            savePlaybackState(); // 保存播放状态
        }
    }
    

    /** 停止并清空当前媒体，进度与时长随之归零；播放意图保留在请求上。 */
    private void stopCurrentMedia() {
        if (exoPlayer == null || destroyed || exoPlayer.getMediaItemCount() == 0) {
            return;
        }
        exoPlayer.stop();
        exoPlayer.clearMediaItems();
    }

    /**
     * 从百度网盘播放歌曲。requestGeneration 用于丢弃过期网络回调，切歌时取消上一请求。
     */
    private void playFromBaiduPan(com.baidu.carplayer.model.Song song, long requestGeneration) {
        Log.d(TAG, "准备播放歌曲: " + song.getTitle() + ", fsId=" + song.getFsId());
        if (pendingUrlRequest != null) {
            pendingUrlRequest.cancel();
        }
        pendingUrlRequest = downloadUrlRepository.resolve(song.getFsId(), new DownloadUrlRepository.Callback() {
            @Override
            public void onSuccess(String playableUrl) {
                if (!requestTracker.isCurrent(requestGeneration)) {
                    Log.d(TAG, "忽略过期的下载链接响应，generation=" + requestGeneration);
                    return;
                }
                pendingUrlRequest = null;
                play(playableUrl);
            }

            @Override
            public void onError(String message, boolean authExpired, @Nullable Throwable cause) {
                if (!requestTracker.isCurrent(requestGeneration)) {
                    return;
                }
                pendingUrlRequest = null;
                Log.e(TAG, "获取下载链接失败: " + message);
                requestTracker.finish(requestGeneration);
                notifyPlayerError(new PlaybackException(message, cause, PlaybackException.ERROR_CODE_UNSPECIFIED));
            }
        });
    }

    /**
     * 检查播放器是否实际加载了媒体项
     * @return true如果播放器有媒体项，false如果播放器为空
     */
    private boolean isPlayerReady() {
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
    private com.baidu.carplayer.model.Song getCurrentSong() {
        return queue.current();
    }

    /** 按稳定 ID 切歌；id 不存在时不改变当前歌曲。 */
    private void playSongById(long songId) {
        if (queue.moveToSongId(songId)) {
            playAtPosition(queue.getIndex());
        }
    }
    
    /**
     * 上一曲
     */
    private void playPrevious() {
        int target = queue.previous();
        if (target != PlaybackQueue.STAY) {
            playAtPosition(target);
        }
    }
    
    /** 下一曲。顺序模式循环，随机模式一轮播完重新洗牌，单曲循环不切换。 */
    private void playNext() {
        int target = queue.next();
        if (target != PlaybackQueue.STAY) {
            playAtPosition(target);
        }
    }
    
    
    /**
     * 设置播放模式
     */
    private void setPlayMode(PlaybackQueue.Mode mode) {
        queue.setMode(mode);
        savePlaybackState(); // 保存播放状态
        publishPlaybackState();
    }
    
    /**
     * 设置随机播放模式
     */
    private void setShuffleMode(boolean shuffle) {
        queue.setMode(shuffle ? PlaybackQueue.Mode.RANDOM : PlaybackQueue.Mode.ORDER);
        publishPlaybackState();
    }
    
    /** 重新生成随机序列（恢复随机模式时使用）。 */
    private void generateShuffleOrder() {
        queue.setMode(PlaybackQueue.Mode.RANDOM);
        Log.d(TAG, "重生成了随机播放序列, size=" + queue.size());
    }
    
    
    /**
     * 暂停播放
     */
    private void pause() {
        requestTracker.setPlayWhenReady(false);
        if (exoPlayer == null || destroyed) {
            return;
        }
        exoPlayer.pause();
        savePlaybackState(); // 暂停时保存状态
        updateMediaSessionState();
    }
    
    
    /**
     * 停止播放
     */
    private void stop() {
        if (exoPlayer == null || destroyed) return;
        exoPlayer.stop();
        abandonAudioFocus();
        stopForeground(true);
        updateMediaSessionState();
    }
    
    
    
    /**
     * 跳转到指定位置
     */
    private void seekTo(long positionMs) {
        if (exoPlayer == null || destroyed) return;
        exoPlayer.seekTo(positionMs);
        publishPlaybackState();
    }
    
    /**
     * 设置音量
     */
    private void setVolume(float volume) {
        userVolume = Math.max(0f, Math.min(1f, volume));
        if (exoPlayer != null && !ducked) {
            exoPlayer.setVolume(userVolume);
        }
        publishPlaybackState();
    }
    

    /**
     * 是否正在播放
     */
    private boolean isPlaying() {
        if (exoPlayer == null) return false;
        return exoPlayer.getPlayWhenReady() && exoPlayer.getPlaybackState() == Player.STATE_READY;
    }
    
    /**
     * 设置播放状态监听器
     */
    /** 所有外部控制入口的统一命令入口；ExoPlayer 只在主线程上被操作。 */
    public void dispatch(PlaybackCommand.Type type) {
        dispatchCommand(PlaybackCommand.simple(type, requestTracker.getGeneration()));
    }

    public void dispatchCommand(PlaybackCommand command) {
        if (command == null || destroyed) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            commandHandler.post(() -> dispatchCommand(command));
            return;
        }
        Log.d(TAG, "dispatchCommand: " + command.getType());
        switch (command.getType()) {
            case PLAY:
                play();
                break;
            case PAUSE:
                pause();
                break;
            case TOGGLE:
                if (isPlaying()) pause(); else play();
                break;
            case NEXT:
                playNext();
                break;
            case PREVIOUS:
                playPrevious();
                break;
            case STOP:
                stop();
                break;
            case SEEK:
                seekTo(command.getSeekPositionMs());
                break;
            case SELECT:
                playSongById(command.getSongId());
                break;
            case SET_PLAY_MODE: {
                PlaybackQueue.Mode[] modes = PlaybackQueue.Mode.values();
                int ordinal = command.getPlayMode();
                setPlayMode(ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : PlaybackQueue.Mode.ORDER);
                break;
            }
            case SET_VOLUME:
                setVolume(command.getVolume());
                break;
            case SAVE_STATE:
                savePlaybackState();
                break;
            default:
                break;
        }
        publishPlaybackState();
    }

    private boolean needsReload() {
        return loadFailed || exoPlayer.getPlayerError() != null || exoPlayer.getMediaItemCount() == 0;
    }

    /**
     * 恢复上次保存的播放会话。播放器已有媒体时只同步状态（热恢复），
     * 否则重新加载歌曲并定位到保存的进度（冷启动）。
     * @return 没有可恢复的会话时返回 false
     */
    public boolean resumeSavedSession() {
        if (queue.isEmpty() || queue.current() == null) {
            return false;
        }
        if (!isPlayerReady() && requestTracker.current() == null) {
            long savedProgress = getSavedProgress();
            Log.d(TAG, "冷启动恢复：重新加载歌曲，进度=" + savedProgress + "ms");
            playAtPosition(queue.getIndex(), false, savedProgress > 0 ? savedProgress : null);
        } else {
            publishPlaybackState();
        }
        return true;
    }

    /**
     * 用新的歌曲列表开始播放。songId 有效时从该歌曲开始，否则使用 fallbackIndex。
     */
    public void startPlaylist(List<com.baidu.carplayer.model.Song> songs, long songId,
                              int fallbackIndex, boolean shuffle) {
        if (songs == null || songs.isEmpty()) return;
        setPlaylist(new ArrayList<>(songs));
        int index = -1;
        if (songId != -1) {
            index = com.baidu.carplayer.model.Song.findSongPosition(queue.getSongs(), songId);
        }
        if (index < 0) {
            index = fallbackIndex >= 0 && fallbackIndex < queue.size() ? fallbackIndex : 0;
        }
        queue.moveTo(index);
        if (shuffle) {
            setShuffleMode(true);
        }
        playAtPosition(index);
    }

    /** 当前不可变状态快照，新代码应优先订阅它而不是读取 Service 的可变字段。 */
    public PlaybackState getPlaybackSnapshot() {
        return playbackCoordinator.getState();
    }

    public void subscribePlaybackState(PlaybackCoordinator.StateSubscriber subscriber) {
        playbackCoordinator.subscribe(subscriber);
    }

    public void unsubscribePlaybackState(PlaybackCoordinator.StateSubscriber subscriber) {
        playbackCoordinator.unsubscribe(subscriber);
    }

    private void publishPlaybackState() {
        if (destroyed) return;
        playbackCoordinator.publish(buildPlaybackState());
    }

    // 队列快照只在播放列表引用变化时重建（setPlaylist 总是替换引用）。
    private List<com.baidu.carplayer.model.Song> queueSnapshotSource;
    private List<com.baidu.carplayer.model.Song> queueSnapshot = java.util.Collections.emptyList();

    private PlaybackState buildPlaybackState() {
        com.baidu.carplayer.model.Song song = getCurrentSong();
        PlaybackState.Status status = PlaybackState.Status.IDLE;
        boolean playWhenReady = false;
        long position = 0L;
        long duration = 0L;
        int audioSessionId = 0;
        if (exoPlayer != null) {
            switch (exoPlayer.getPlaybackState()) {
                case Player.STATE_BUFFERING:
                    status = PlaybackState.Status.BUFFERING;
                    break;
                case Player.STATE_READY:
                    status = PlaybackState.Status.READY;
                    break;
                case Player.STATE_ENDED:
                    status = PlaybackState.Status.ENDED;
                    break;
                default:
                    status = PlaybackState.Status.IDLE;
            }
            playWhenReady = exoPlayer.getPlayWhenReady();
            position = Math.max(0L, exoPlayer.getCurrentPosition());
            duration = Math.max(0L, exoPlayer.getDuration());
            audioSessionId = exoPlayer.getAudioSessionId();
        }
        PlaybackCommand command = requestTracker.current();
        if (command != null && status == PlaybackState.Status.IDLE) {
            // 正在获取下载地址：显示缓冲，并反映该请求的 autoplay 意图。
            status = PlaybackState.Status.BUFFERING;
            playWhenReady = command.isPlayWhenReady();
        } else if (command == null && loadFailed) {
            // 失败状态持续到下一次成功加载，避免进度发布把错误“覆盖”成 IDLE。
            status = PlaybackState.Status.ERROR;
            playWhenReady = false;
        }
        List<com.baidu.carplayer.model.Song> songs = queue.getSongs();
        if (queueSnapshotSource != songs) {
            queueSnapshotSource = songs;
            queueSnapshot = PlaybackState.snapshotQueue(songs);
        }
        return new PlaybackState(queueSnapshot, queue.getIndex(), song != null ? song.getId() : -1L,
                status, playWhenReady, position, duration, queue.getMode().ordinal(), audioSessionId,
                requestTracker.getGeneration()).withPlayerDetails(userVolume, currentAudioFormat());
    }

    private void notifyPlayerError(PlaybackException error) {
        loadFailed = true;
        if (!destroyed) {
            playbackCoordinator.publish(buildPlaybackState().withError(error.getMessage()));
        }
    }


    /** 用当前歌曲与播放状态刷新通知（同时同步 MediaSession）。 */
    private void updateNotification() {
        updateMediaSessionState();
        notifier.notify(mediaSession, queue.current(), isPlaying());
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 系统方向盘/蓝牙媒体按键会通过 MEDIA_BUTTON Intent 投递给当前活动的 MediaSession。
        // 必须显式交给 MediaButtonReceiver，MediaSessionCompat 才会触发上面的回调。
        if (mediaSessionBridge != null && mediaSessionBridge.handleMediaButton(intent)) {
            return START_STICKY;
        }

        // 处理通知栏按钮等自定义控制动作
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
                dispatch(PlaybackCommand.Type.PLAY);
                break;
            case ACTION_PAUSE:
                dispatch(PlaybackCommand.Type.PAUSE);
                break;
            case ACTION_PLAY_PAUSE:
                dispatch(PlaybackCommand.Type.TOGGLE);
                break;
            case ACTION_NEXT:
                dispatch(PlaybackCommand.Type.NEXT);
                break;
            case ACTION_PREVIOUS:
                dispatch(PlaybackCommand.Type.PREVIOUS);
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
        // 先使所有异步播放回调失效，再释放播放器。
        destroyed = true;
        requestTracker.close();
        if (pendingUrlRequest != null) {
            pendingUrlRequest.cancel();
            pendingUrlRequest = null;
        }
        commandHandler.removeCallbacksAndMessages(null);
        playbackCoordinator.close();
        super.onDestroy();
        stopProgressSave();
        savePlaybackState(); // 在服务销毁前保存状态
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        abandonAudioFocus();
        if (mediaSessionBridge != null) {
            mediaSessionBridge.release();
            mediaSessionBridge = null;
            mediaSession = null;
        }
    }
    
    /**
     * 保存播放状态（委托给 PlaybackPersistence）
     */
    private void savePlaybackState() {
        if (queue.isEmpty()) {
            return;
        }
        long progressMs = exoPlayer != null ? exoPlayer.getCurrentPosition() : 0;
        boolean isPlaying = exoPlayer != null && exoPlayer.getPlayWhenReady();
        persistence.save(queue.getSongs(), queue.getIndex(), progressMs, queue.getMode().name(), isPlaying);
        Log.d(TAG, "播放状态已保存: position=" + queue.getIndex() + ", mode=" + queue.getMode());
    }
    
    /**
     * 恢复播放状态（委托给 PlaybackPersistence）
     */
    private void restorePlaybackState() {
        List<com.baidu.carplayer.model.Song> saved = persistence.loadQueue();
        if (saved == null) {
            Log.d(TAG, "没有保存的播放状态");
            return;
        }
        queue.setSongs(saved);
        queue.moveTo(persistence.loadPosition());
        queue.setMode(PlayMode.fromName(persistence.loadPlayModeName()));
        // setMode(RANDOM) 已经重建了洗牌序列，这里不再重复生成。
        Log.d(TAG, "播放状态已恢复: playlist size=" + queue.size()
                + ", position=" + queue.getIndex() + ", mode=" + queue.getMode());
    }
    
    /**
     * 获取保存的播放进度（毫秒）
     */
    private long getSavedProgress() {
        return persistence.loadProgress();
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
        if (mediaSessionBridge == null) return;
        mediaSessionBridge.update(exoPlayer != null,
                exoPlayer != null && exoPlayer.getPlayWhenReady(),
                exoPlayer != null ? exoPlayer.getCurrentPosition() : 0);
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
    
    /** 当前解码格式；只在播放器就绪后可用。 */
    @Nullable
    private AudioFormat currentAudioFormat() {
        if (exoPlayer == null || exoPlayer.getAudioFormat() == null) {
            return null;
        }
        androidx.media3.common.Format format = exoPlayer.getAudioFormat();
        return new AudioFormat(format.sampleMimeType, format.sampleRate, format.bitrate, format.channelCount);
    }
}