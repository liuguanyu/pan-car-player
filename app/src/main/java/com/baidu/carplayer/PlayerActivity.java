package com.baidu.carplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.CurrentPlaylistAdapter;

import com.baidu.carplayer.manager.LyricsManager;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.service.AudioPlayerService;
import com.baidu.carplayer.utils.LrcParser;
import com.baidu.carplayer.widget.LrcView;
import com.baidu.carplayer.widget.RotatingDiscView;
import com.baidu.carplayer.widget.ParticleBackgroundView;
import androidx.media3.common.PlaybackException;

import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import android.text.TextPaint;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 播放页面 - 包含歌词、控制、音量、播放模式、进度条
 */
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    public static final String EXTRA_PLAYLIST_NAME = "playlist_name";
    public static final String EXTRA_POSITION = "position";
    public static final String EXTRA_SONG_ID = "song_id";
    public static final String EXTRA_SORT_ASCENDING = "sort_ascending";
    public static final String EXTRA_RESUME_PLAYBACK = "resume_playback";

    // UI组件
    private TextView songTitle;
    private TextView songAlbum;
    private LinearLayout headerLayout;
    private TextView currentTime;
    private TextView audioInfo;
    private TextView totalTime;
    private SeekBar progressBar;
    private SeekBar volumeBar;
    private ImageButton backButton;
    private ImageButton playPauseButton;
    private ImageButton previousButton;
    private ImageButton nextButton;
    private ImageButton playModeButton;
    private ImageButton volumeButton;
    private ImageButton playlistButton;
    private LrcView lrcView;
    private RotatingDiscView rotatingDiscView;
    private com.baidu.carplayer.widget.RotatingTextView rotatingTextView;
    private ParticleBackgroundView particleBackgroundView;
    
    // 播放列表相关
    private FrameLayout playlistContainer;
    private RecyclerView playlistRecyclerView;
    private EditText playlistSearchInput;
    private ImageButton closePlaylistButton;
    private CurrentPlaylistAdapter currentPlaylistAdapter;
    private boolean playlistVisible = false;

    // 服务相关
    private AudioPlayerService audioPlayerService;
    private boolean serviceBound = false;
    private Handler handler = new Handler();
    private Runnable updateProgressRunnable;
    
    // 播放错误处理
    private boolean hasPlaybackError = false;

    // 数据管理
    private PlaylistManager playlistManager;
    private LyricsManager lyricsManager;
    private String playlistId;
    private String playlistName;
    private int currentPosition;
    private long songId; // 要播放的歌曲ID
    private boolean sortAscending = true; // 排序状态
    private List<Song> playlistSongs;
    private Song currentSong;
    private boolean volumeControlVisible = false;
    private boolean isMuted = false;
    private float lastVolume = 0.5f;
    
    // 流光动画相关
    private ValueAnimator shimmerAnimator;
    private float shimmerOffset = 0f;
    private int shimmerColor1 = Color.rgb(255, 255, 255);
    private int shimmerColor2 = Color.rgb(200, 200, 255);

    // 服务连接
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            audioPlayerService = binder.getService();
            serviceBound = true;
            
            // 设置播放状态监听器
            audioPlayerService.setPlaybackStateListener(new AudioPlayerService.PlaybackStateListener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    // 播放状态改变时更新UI
                    updatePlayPauseButton();
                    updateProgress();
                    updateParticleBackground();
                }
                
                @Override
                public void onPlayWhenReadyChanged(boolean playWhenReady) {
                    // 播放准备状态改变时更新UI
                    updatePlayPauseButton();
                    updateParticleBackground();
                }
                
                @Override
                public void onPlayerError(PlaybackException error) {
                    // 处理播放错误
                    handlePlaybackError(error);
                }
                
                @Override
                public void onPositionDiscontinuity() {
                    // 位置不连续时更新UI
                    updatePlayerState();
                }
            });

            // 检查是否需要恢复播放状态
            if (getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false)) {
                resumePlaybackState();
            } else {
                // 加载播放列表并开始播放
                loadPlaylistAndPlay();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        // 获取传入的参数
        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);
        currentPosition = getIntent().getIntExtra(EXTRA_POSITION, 0);
        songId = getIntent().getLongExtra(EXTRA_SONG_ID, -1);
        sortAscending = getIntent().getBooleanExtra(EXTRA_SORT_ASCENDING, true);
        final boolean shuffleMode = getIntent().getBooleanExtra("EXTRA_SHUFFLE_MODE", false);
        final boolean resumePlayback = getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false);

        // 如果是恢复播放模式，不需要 playlistId
        if (!resumePlayback && playlistId == null) {
            finish();
            return;
        }

        initViews();
        initData();
        
        // 初始化顶部标题栏渐变背景
        updateHeaderGradient();
        
        bindPlayerService();

        // 如果是恢复播放模式，设置到服务中（需要在服务连接后）
        if (resumePlayback) {
            // 延迟恢复播放状态，等待服务连接
            handler.postDelayed(() -> {
                if (serviceBound && audioPlayerService != null) {
                    resumePlaybackState();
                }
            }, 500);
        } else if (shuffleMode) {
            // 如果是随机播放模式，设置到服务中（需要在服务连接后）
            handler.postDelayed(() -> {
                if (serviceBound && audioPlayerService != null) {
                    audioPlayerService.setShuffleMode(true);
                }
            }, 500);
        }
    }

    private void initViews() {
        songTitle = findViewById(R.id.song_title);
        songAlbum = findViewById(R.id.song_album);
        headerLayout = findViewById(R.id.header_layout);
        currentTime = findViewById(R.id.current_time);
        audioInfo = findViewById(R.id.audio_info);
        totalTime = findViewById(R.id.total_time);
        progressBar = findViewById(R.id.progress_bar);
        volumeBar = findViewById(R.id.volume_bar);
        backButton = findViewById(R.id.back_button);
        playPauseButton = findViewById(R.id.play_pause_button);
        previousButton = findViewById(R.id.previous_button);
        nextButton = findViewById(R.id.next_button);
        playModeButton = findViewById(R.id.play_mode_button);
        volumeButton = findViewById(R.id.volume_button);
        lrcView = findViewById(R.id.lrc_view);
        rotatingDiscView = findViewById(R.id.rotating_disc);
        rotatingTextView = findViewById(R.id.rotating_text);
        particleBackgroundView = findViewById(R.id.particle_background);
        playlistButton = findViewById(R.id.playlist_button);
        
        // 播放列表相关视图
        playlistContainer = findViewById(R.id.playlist_container);
        playlistRecyclerView = findViewById(R.id.playlist_recycler_view);
        playlistSearchInput = findViewById(R.id.playlist_search_input);
        closePlaylistButton = findViewById(R.id.close_playlist_button);

        // 设置按钮点击事件
        backButton.setOnClickListener(v -> onBackPressed());
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        previousButton.setOnClickListener(v -> playPrevious());
        nextButton.setOnClickListener(v -> playNext());
        playModeButton.setOnClickListener(v -> togglePlayMode());
        volumeButton.setOnClickListener(v -> toggleMute());
        volumeButton.setOnLongClickListener(v -> {
            toggleVolumeControl();
            return true;
        });
        playlistButton.setOnClickListener(v -> togglePlaylist());
        closePlaylistButton.setOnClickListener(v -> hidePlaylist());
        
        // 点击背景关闭播放列表
        playlistContainer.setOnClickListener(v -> hidePlaylist());
        
        // 设置播放列表
        setupPlaylist();

        // 进度条拖动事件
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && serviceBound) {
                    audioPlayerService.seekTo(progress);
                    if (lrcView != null) {
                        lrcView.updateTime(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (updateProgressRunnable != null) {
                    handler.removeCallbacks(updateProgressRunnable);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                updateProgress();
            }
        });

        // 音量条事件
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && serviceBound) {
                    float volume = progress / 100.0f;
                    audioPlayerService.setVolume(volume);
                    
                    // 如果用户拖动音量条，取消静音状态
                    if (isMuted && volume > 0) {
                        isMuted = false;
                        lastVolume = volume;
                        updateVolumeButton();
                    }
                    
                    // 更新保存的音量
                    if (volume > 0) {
                        lastVolume = volume;
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 初始隐藏音量控制
        volumeBar.setVisibility(View.GONE);
    }

    private void initData() {
        playlistManager = new PlaylistManager(this);
        lyricsManager = new LyricsManager(this);
    }

    private void bindPlayerService() {
        Intent intent = new Intent(this, AudioPlayerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        startService(intent); // 确保服务启动
    }

    private void resumePlaybackState() {
        if (!serviceBound || audioPlayerService == null) return;
        
        // 获取保存的播放列表和位置
        List<Song> savedPlaylist = audioPlayerService.getSavedPlaylist();
        int savedPosition = audioPlayerService.getSavedPosition();
        
        if (savedPlaylist != null && !savedPlaylist.isEmpty() && savedPosition >= 0 && savedPosition < savedPlaylist.size()) {
            playlistSongs = savedPlaylist;
            currentPosition = savedPosition;
            
            // 检查是热恢复还是冷启动
            // 使用isPlayerReady()检查播放器是否实际加载了媒体项
            // 如果播放器没有媒体项，说明是冷启动（即使Service还在运行）
            boolean playerReady = audioPlayerService.isPlayerReady();
            currentSong = audioPlayerService.getCurrentSong();
            
            if (!playerReady) {
                // 冷启动：播放器没有加载媒体项，需要重新加载歌曲并定位到保存的播放位置
                Log.d("PlayerActivity", "冷启动：播放器无媒体项，重新加载歌曲并恢复播放位置");
                
                // 从服务获取保存的播放进度（毫秒）
                long savedProgress = audioPlayerService.getSavedProgress();
                Log.d("PlayerActivity", "获取到保存的播放进度: " + savedProgress + "ms");
                
                // 重新加载歌曲，并在播放器准备好后自动seek到保存的位置
                // savedProgress > 0 时传入进度，否则传null从头播放
                audioPlayerService.playAtPosition(savedPosition, false, savedProgress > 0 ? savedProgress : null);
                
                currentSong = audioPlayerService.getCurrentSong();
            } else {
                // 热恢复：播放器已有媒体项，直接同步UI
                Log.d("PlayerActivity", "热恢复：播放器有媒体项，直接同步当前播放状态");
            }
            
            // 更新UI以反映当前播放状态
            updatePlayerState();
            updatePlayPauseButton();
            updatePlayModeButton();
            updateParticleBackground();
            
            // 启动进度更新
            startUpdateProgress();
        } else {
            // 如果没有保存的播放状态
            Toast.makeText(this, "没有保存的播放状态", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadPlaylistAndPlay() {
        // 如果是恢复播放，不需要加载列表
        if (getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false)) {
            resumePlaybackState();
            return;
        }

        playlistManager.getSongsForPlaylist(playlistId, new PlaylistManager.OnSongsLoadListener() {
            @Override
            public void onSuccess(List<Song> songs) {
                runOnUiThread(() -> {
                    if (songs.isEmpty()) {
                        Toast.makeText(PlayerActivity.this, "播放列表为空", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    playlistSongs = songs;

                    // 应用与SongListActivity相同的排序规则
                    Song.sortSongs(playlistSongs, sortAscending);

                    // 确定要播放的位置
                    int positionToPlay = currentPosition;
                    if (songId != -1) {
                        // 如果传入了歌曲ID，找到该歌曲在排序后列表中的位置
                        positionToPlay = Song.findSongPosition(playlistSongs, songId);
                        if (positionToPlay == -1) {
                            // 如果找不到歌曲，使用默认位置
                            positionToPlay = 0;
                        }
                    }

                    // 设置播放列表到服务
                    if (serviceBound) {
                        audioPlayerService.setPlaylist(playlistSongs);
                        audioPlayerService.playAtPosition(positionToPlay);

                        // 更新UI
                        updatePlayerState();
                        updateProgress();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Toast.makeText(PlayerActivity.this, "加载播放列表失败: " + error, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }
    
    /**
     * 处理播放错误
     */
    private void handlePlaybackError(PlaybackException error) {
        runOnUiThread(() -> {
            hasPlaybackError = true;
            String errorMessage = "播放失败: " + (error.getMessage() != null ? error.getMessage() : "未知错误");
            Toast.makeText(PlayerActivity.this, errorMessage, Toast.LENGTH_LONG).show();
            
            // 更新UI状态
            playPauseButton.setImageResource(R.drawable.ic_play);
            playPauseButton.setContentDescription("播放");
        });
    }
    
    /**
     * 更新粒子背景状态
     */
    private void updateParticleBackground() {
        if (particleBackgroundView != null && audioPlayerService != null) {
            boolean isPlaying = audioPlayerService.isPlaying();
            particleBackgroundView.setPlaying(isPlaying);
        }
    }
    
    /**
     * 更新MediaSession元数据
     */

    private void updatePlayerState() {
        if (!serviceBound) return;

        currentSong = audioPlayerService.getCurrentSong();
        if (currentSong == null) return;

        // 更新歌曲信息（使用去除扩展名的标题）
        String displayTitle = currentSong.getTitleWithoutExtension();
        songTitle.setText(displayTitle);
        songAlbum.setText(currentSong.getAlbum());
        // 更新唱片标题
        if (rotatingDiscView != null) {
            rotatingDiscView.setTitle(displayTitle);
        }
        if (rotatingTextView != null) {
            rotatingTextView.setText(displayTitle);
        }
        
        // 更新歌名渐变色
        updateTitleGradient();

        // 更新时长 - 从播放器获取实际时长
        long duration = audioPlayerService.getDuration();
        if (duration > 0) {
            long minutes = duration / 60000;
            long seconds = (duration % 60000) / 1000;
            totalTime.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));
        } else {
            totalTime.setText("00:00");
        }

        // 更新播放/暂停按钮
        updatePlayPauseButton();

        // 更新播放模式按钮
        updatePlayModeButton();

        // 更新粒子背景
        updateParticleBackground();

        // 更新音频信息（立即更新一次）
        updateAudioInfo();
        
        // 延迟更新音频信息，因为 ExoPlayer 需要时间解析音频格式
        handler.postDelayed(() -> {
            updateAudioInfo();
        }, 500);
        
        // 再次延迟更新，确保获取到完整的音频信息
        handler.postDelayed(() -> {
            updateAudioInfo();
        }, 1500);

        // 加载歌词
        loadLyrics();
    }

    private void loadLyrics() {
        if (currentSong == null) {
            lrcView.setLrcEntries(null);
            return;
        }

        lyricsManager.loadLyrics(currentSong, new LyricsManager.OnLyricsLoadListener() {
            @Override
            public void onLyricsLoaded(List<LrcParser.LrcEntry> lrcEntries) {
                runOnUiThread(() -> {
                    if (lrcEntries != null && !lrcEntries.isEmpty()) {
                        lrcView.setLrcEntries(lrcEntries);
                        
                        // 如果正在播放，同步歌词进度
                        if (serviceBound && audioPlayerService.isPlaying()) {
                            lrcView.updateTime(audioPlayerService.getCurrentPosition());
                        }
                    } else {
                        lrcView.setLrcEntries(null);
                    }
                });
            }

            @Override
            public void onLyricsLoadFailed(String error) {
                runOnUiThread(() -> lrcView.setLrcEntries(null));
            }
        });
    }

    private void togglePlayPause() {
        if (serviceBound) {
            if (hasPlaybackError) {
                // 如果有播放错误，重新加载当前歌曲
                hasPlaybackError = false;
                if (audioPlayerService.getCurrentSong() != null) {
                    audioPlayerService.playSong(audioPlayerService.getCurrentSong());
                }
                return;
            }
            
            if (audioPlayerService.isPlaying()) {
                audioPlayerService.pause();
            } else {
                audioPlayerService.play();
            }
            updatePlayPauseButton();
        }
    }

    private void playPrevious() {
        if (serviceBound) {
            audioPlayerService.playPrevious();
            updatePlayerState();
        }
    }

    private void playNext() {
        if (serviceBound) {
            audioPlayerService.playNext();
            updatePlayerState();
        }
    }

    private void togglePlayMode() {
        if (serviceBound) {
            AudioPlayerService.PlayMode currentMode = audioPlayerService.getPlayMode();
            AudioPlayerService.PlayMode nextMode;

            switch (currentMode) {
                case ORDER:
                    nextMode = AudioPlayerService.PlayMode.RANDOM;
                    Toast.makeText(this, "随机播放", Toast.LENGTH_SHORT).show();
                    break;
                case RANDOM:
                    nextMode = AudioPlayerService.PlayMode.SINGLE;
                    Toast.makeText(this, "单曲循环", Toast.LENGTH_SHORT).show();
                    break;
                case SINGLE:
                default:
                    nextMode = AudioPlayerService.PlayMode.ORDER;
                    Toast.makeText(this, "顺序播放", Toast.LENGTH_SHORT).show();
                    break;
            }

            audioPlayerService.setPlayMode(nextMode);
            updatePlayModeButton();
        }
    }

    private void toggleVolumeControl() {
        volumeControlVisible = !volumeControlVisible;
        volumeBar.setVisibility(volumeControlVisible ? View.VISIBLE : View.GONE);

        if (volumeControlVisible && serviceBound) {
            // 初始化音量条位置
            float currentVolume = audioPlayerService.getVolume();
            volumeBar.setProgress((int) (currentVolume * 100));
        }
    }
    
    /**
     * 切换静音状态
     */
    private void toggleMute() {
        if (!serviceBound) return;
        
        if (isMuted) {
            // 取消静音，恢复之前的音量
            isMuted = false;
            audioPlayerService.setVolume(lastVolume);
            volumeBar.setProgress((int) (lastVolume * 100));
            Toast.makeText(this, "已取消静音", Toast.LENGTH_SHORT).show();
        } else {
            // 静音，保存当前音量
            float currentVolume = audioPlayerService.getVolume();
            if (currentVolume > 0) {
                lastVolume = currentVolume;
            }
            isMuted = true;
            audioPlayerService.setVolume(0);
            volumeBar.setProgress(0);
            Toast.makeText(this, "已静音", Toast.LENGTH_SHORT).show();
        }
        
        updateVolumeButton();
    }
    
    /**
     * 更新音量按钮图标
     */
    private void updateVolumeButton() {
        if (isMuted) {
            volumeButton.setImageResource(R.drawable.ic_volume_mute);
            volumeButton.setContentDescription("取消静音");
        } else {
            volumeButton.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
            volumeButton.setContentDescription("静音");
        }
    }
    
    /**
     * 设置播放列表
     */
    private void setupPlaylist() {
        // 初始化适配器
        currentPlaylistAdapter = new CurrentPlaylistAdapter();
        playlistRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        playlistRecyclerView.setAdapter(currentPlaylistAdapter);
        
        // 设置点击事件
        currentPlaylistAdapter.setOnSongClickListener((song, position) -> {
            // 获取歌曲在原始列表中的位置
            int originalPosition = currentPlaylistAdapter.getOriginalPosition(song);
            if (originalPosition >= 0 && serviceBound) {
                audioPlayerService.playAtPosition(originalPosition);
                updatePlayerState();
                hidePlaylist();
            }
        });
        
        // 设置搜索功能
        playlistSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (currentPlaylistAdapter != null) {
                    currentPlaylistAdapter.getFilter().filter(s);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        
        // 初始隐藏播放列表
        playlistContainer.setVisibility(View.GONE);
    }
    
    /**
     * 切换播放列表显示状态
     */
    private void togglePlaylist() {
        if (playlistVisible) {
            hidePlaylist();
        } else {
            showPlaylist();
        }
    }
    
    /**
     * 显示播放列表
     */
    private void showPlaylist() {
        if (serviceBound && audioPlayerService != null) {
            List<Song> currentPlaylist = audioPlayerService.getSavedPlaylist();
            if (currentPlaylist != null && !currentPlaylist.isEmpty()) {
                currentPlaylistAdapter.setSongs(currentPlaylist);
                
                // 高亮当前播放歌曲
                Song currentSong = audioPlayerService.getCurrentSong();
                if (currentSong != null) {
                    currentPlaylistAdapter.setCurrentPlayingSongId(currentSong.getId());
                }
                
                playlistContainer.setVisibility(View.VISIBLE);
                playlistVisible = true;
                
                // 清空搜索框
                playlistSearchInput.setText("");
                
                // 滚动到当前播放位置
                int currentPosition = audioPlayerService.getSavedPosition();
                if (currentPosition >= 0 && currentPosition < currentPlaylist.size()) {
                    playlistRecyclerView.scrollToPosition(currentPosition);
                }
            } else {
                Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 隐藏播放列表
     */
    private void hidePlaylist() {
        playlistContainer.setVisibility(View.GONE);
        playlistVisible = false;
    }

    private void updatePlayPauseButton() {
        if (serviceBound) {
            if (audioPlayerService.isPlaying()) {
                playPauseButton.setImageResource(R.drawable.ic_pause);
                playPauseButton.setContentDescription("暂停");
                if (rotatingDiscView != null) {
                    rotatingDiscView.resumeRotation();
                }
                if (rotatingTextView != null) {
                    rotatingTextView.resumeRotation();
                }
                // 播放时启动流光动画
                startShimmerAnimation();
            } else {
                playPauseButton.setImageResource(R.drawable.ic_play);
                if (rotatingDiscView != null) {
                    rotatingDiscView.pauseRotation();
                }
                if (rotatingTextView != null) {
                    rotatingTextView.pauseRotation();
                }
                playPauseButton.setContentDescription("播放");
                // 暂停时停止流光动画
                stopShimmerAnimation();
            }
        }
    }

    private void updatePlayModeButton() {
        if (serviceBound) {
            AudioPlayerService.PlayMode mode = audioPlayerService.getPlayMode();
            switch (mode) {
                case ORDER:
                    playModeButton.setImageResource(R.drawable.ic_repeat);
                    playModeButton.setContentDescription("顺序播放");
                    break;
                case RANDOM:
                    playModeButton.setImageResource(R.drawable.ic_shuffle);
                    playModeButton.setContentDescription("随机播放");
                    break;
                case SINGLE:
                    playModeButton.setImageResource(R.drawable.ic_repeat_one);
                    playModeButton.setContentDescription("单曲循环");
                    break;
            }
        }
    }

    private void updateProgress() {
        if (serviceBound && audioPlayerService.isPlaying()) {
            long currentPosition = audioPlayerService.getCurrentPosition();
            long duration = audioPlayerService.getDuration();

            // 更新进度条
            if (duration > 0) {
                progressBar.setMax((int) duration);
                progressBar.setProgress((int) currentPosition);

                // 更新时间显示
                long currentMinutes = currentPosition / 60000;
                long currentSeconds = (currentPosition % 60000) / 1000;
                currentTime.setText(String.format(Locale.getDefault(), "%02d:%02d", currentMinutes, currentSeconds));
    
                // 更新总时长显示（确保在获取到时长后更新）
                long totalMinutes = duration / 60000;
                long totalSeconds = (duration % 60000) / 1000;
                totalTime.setText(String.format(Locale.getDefault(), "%02d:%02d", totalMinutes, totalSeconds));
    
                // 更新歌词位置
                if (lrcView != null) {
                    lrcView.updateTime(currentPosition);
                }
            } else {
                if (rotatingDiscView != null && rotatingDiscView.isRotating()) {
                    rotatingDiscView.pauseRotation();
                }
                if (rotatingTextView != null && rotatingTextView.isRotating()) {
                    rotatingTextView.pauseRotation();
                }
            }
        }
        
        // 每秒更新一次
        updateProgressRunnable = this::updateProgress;
        handler.postDelayed(updateProgressRunnable, 1000);
    }

    private void startUpdateProgress() {
        // 先移除之前的回调，避免重复更新
        if (updateProgressRunnable != null) {
            handler.removeCallbacks(updateProgressRunnable);
        }
        // 开始更新进度
        updateProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (handler != null && updateProgressRunnable != null) {
            handler.removeCallbacks(updateProgressRunnable);
        }
        // 停止流光动画
        stopShimmerAnimation();
    }
    
    /**
     * 更新音频信息显示
     */
    private void updateAudioInfo() {
        if (!serviceBound || currentSong == null || audioInfo == null) return;
        
        // 获取音频格式信息
        AudioPlayerService.AudioFormatInfo formatInfo = audioPlayerService.getAudioFormatInfo();
        
        // 格式化显示
        StringBuilder info = new StringBuilder();
        
        // 音频格式
        if (formatInfo != null && formatInfo.sampleMimeType != null) {
            String format = formatInfo.sampleMimeType.toLowerCase();
            if (format.contains("mpeg") || format.contains("mp3")) {
                info.append("MP3");
            } else if (format.contains("flac")) {
                info.append("FLAC");
            } else if (format.contains("alac")) {
                info.append("ALAC");
            } else if (format.contains("aac")) {
                info.append("AAC");
            } else if (format.contains("wav") || format.contains("pcm")) {
                info.append("WAV");
            } else if (format.contains("ogg") || format.contains("vorbis") || format.contains("opus")) {
                info.append("OGG");
            } else if (format.contains("wma") || format.contains("asf")) {
                info.append("WMA");
            } else if (format.contains("m4a")) {
                info.append("M4A");
            } else if (format.contains("ape")) {
                info.append("APE");
            } else {
                info.append(format.toUpperCase());
            }
            
            // 采样率
            if (formatInfo.sampleRate > 0) {
                info.append(" | ").append(formatInfo.sampleRate / 1000).append("kHz");
            }
            
            // 比特率
            if (formatInfo.bitrate > 0) {
                info.append(" | ").append(formatInfo.bitrate / 1000).append("kbps");
            }
        } else {
            // 如果无法获取格式信息，尝试使用文件扩展名
            String extension = currentSong.getExtension();
            if (!extension.isEmpty()) {
                info.append(extension);
            } else {
                info.append("--");
            }
        }
        
        // 文件大小
        long size = currentSong.getSize();
        if (size > 0) {
            info.append(" | ").append(formatFileSize(size));
        }
        
        // 列表曲目数
        int totalSongs = playlistSongs != null ? playlistSongs.size() : 0;
        if (totalSongs > 0) {
            info.append(" | 共").append(totalSongs).append("首");
        }
        
        // 显示到 TextView
        audioInfo.setText(info.toString());
    }
    
    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0);
        } else {
            return String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024.0));
        }
    }
    
    /**
     * 更新歌名渐变色
     */
    private void updateTitleGradient() {
        if (songTitle == null) return;
        
        Random random = new Random();
        shimmerColor1 = Color.rgb(200 + random.nextInt(56), 200 + random.nextInt(56), 255);
        shimmerColor2 = Color.rgb(255, 200 + random.nextInt(56), 200 + random.nextInt(56));
        
        // 启动流光动画
        startShimmerAnimation();
        
        // 同时更新顶部标题栏背景渐变
        updateHeaderGradient();
    }
    
    /**
     * 启动流光动画
     */
    private void startShimmerAnimation() {
        if (shimmerAnimator != null && shimmerAnimator.isRunning()) {
            shimmerAnimator.cancel();
        }
        
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(4000); // 增加到4秒，让流光效果更缓慢优雅
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
        shimmerAnimator.setInterpolator(new LinearInterpolator());
        
        shimmerAnimator.addUpdateListener(animation -> {
            shimmerOffset = (float) animation.getAnimatedValue();
            updateShimmerGradient();
        });
        
        shimmerAnimator.start();
    }
    
    /**
     * 停止流光动画
     */
    private void stopShimmerAnimation() {
        if (shimmerAnimator != null && shimmerAnimator.isRunning()) {
            shimmerAnimator.cancel();
        }
    }
    
    /**
     * 更新流光渐变效果
     */
    private void updateShimmerGradient() {
        if (songTitle == null) return;
        
        TextPaint paint = songTitle.getPaint();
        String text = songTitle.getText().toString();
        float textWidth = paint.measureText(text);
        
        // 保持原有的水平渐变方向，让颜色位置随时间偏移产生流动效果
        // 使用TileMode.MIRROR使渐变来回移动
        float offset = shimmerOffset * textWidth;
        
        // 创建多色渐变，颜色始终可见，只是位置在移动
        Shader textShader = new LinearGradient(
                -offset, 0, textWidth - offset, songTitle.getTextSize(),
                new int[]{shimmerColor1, shimmerColor2, shimmerColor1},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.MIRROR);
        
        songTitle.getPaint().setShader(textShader);
        songTitle.invalidate();
    }

    /**
     * 更新顶部标题栏背景渐变色
     */
    private void updateHeaderGradient() {
        if (headerLayout == null) return;

        Random random = new Random();
        // 生成两个随机颜色，保持较深的色调作为背景
        // 使用较低的亮度值 (0-100) 确保背景不会太亮，影响白色文字的可读性
        int r1 = random.nextInt(80);
        int g1 = random.nextInt(80);
        int b1 = random.nextInt(120); // 稍微偏蓝/紫
        
        int r2 = random.nextInt(100);
        int g2 = random.nextInt(60);
        int b2 = random.nextInt(140); // 稍微偏紫/红

        int color1 = Color.rgb(r1, g1, b1);
        int color2 = Color.rgb(r2, g2, b2);

        GradientDrawable gradientDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{color1, color2});
        
        headerLayout.setBackground(gradientDrawable);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 当Activity进入后台时保存播放进度
        if (serviceBound && audioPlayerService != null) {
            audioPlayerService.savePlaybackState();
            Log.d("PlayerActivity", "onPause: 保存播放状态");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 当Activity完全不可见时再次保存播放进度（双重保险）
        if (serviceBound && audioPlayerService != null) {
            audioPlayerService.savePlaybackState();
            Log.d("PlayerActivity", "onStop: 保存播放状态");
        }
    }

    @Override
    public void onBackPressed() {
        // 如果播放列表可见，先关闭播放列表
        if (playlistVisible) {
            hidePlaylist();
            return;
        }
        
        // 如果不是任务栈的根Activity，说明下面还有Activity（如SongListActivity），直接返回即可
        if (!isTaskRoot()) {
            super.onBackPressed();
            return;
        }

        // 如果是从恢复播放模式进入（即从SplashActivity直接跳转过来），且是根Activity
        if (getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false)) {
            // 获取当前歌曲所属的播放列表
            if (serviceBound && audioPlayerService != null) {
                Song currentSong = audioPlayerService.getCurrentSong();
                android.util.Log.d("PlayerActivity", "Current song: " + (currentSong != null ? currentSong.getTitle() : "null"));
                if (currentSong != null) {
                    android.util.Log.d("PlayerActivity", "Playlist ID: " + currentSong.getPlaylistId());
                    if (currentSong.getPlaylistId() != null) {
                        // 跳转到歌曲所在的播放列表页面
                        Intent intent = new Intent(this, SongListActivity.class);
                        intent.putExtra(SongListActivity.EXTRA_PLAYLIST_ID, currentSong.getPlaylistId());
                        // 从数据库获取播放列表名称（如果有的话，这里先传空，让SongListActivity从数据库获取）
                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        finish();
                        return;
                    } else {
                        android.util.Log.d("PlayerActivity", "Playlist ID is null");
                    }
                } else {
                    android.util.Log.d("PlayerActivity", "Current song is null");
                }
            } else {
                android.util.Log.d("PlayerActivity", "Service not bound or audioPlayerService is null");
            }
            // 如果无法获取播放列表信息，则跳转到MainActivity
            android.util.Log.d("PlayerActivity", "Falling back to MainActivity");
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else {
            // 正常的返回行为
            super.onBackPressed();
        }
    }
}