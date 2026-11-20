package com.baidu.carplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.CurrentPlaylistAdapter;

import com.baidu.carplayer.manager.LyricsManager;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.playback.MediaKeyDispatcher;
import com.baidu.carplayer.playback.AudioFormat;
import com.baidu.carplayer.playback.PlaybackCommand;
import com.baidu.carplayer.playback.PlaybackCoordinator;
import com.baidu.carplayer.playback.PlaybackState;
import com.baidu.carplayer.player.LyricsSession;
import com.baidu.carplayer.player.PlayModeCycle;
import com.baidu.carplayer.player.PlayerViewState;
import com.baidu.carplayer.player.SeekGesture;
import com.baidu.carplayer.player.VolumeState;
import com.baidu.carplayer.service.AudioPlayerService;
import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.utils.LrcParser;
import com.baidu.carplayer.widget.LrcView;
import com.baidu.carplayer.widget.RotatingDiscView;
import com.baidu.carplayer.widget.ParticleBackgroundView;

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
    private LinearLayout controlBar;
    private com.baidu.carplayer.widget.LcdTextView speedText;
    private TextView speedUnit;
    /** 实时车速（GPS）：只用于显示与配色，不记录轨迹。 */
    private com.baidu.carplayer.vehicle.SpeedMonitor speedMonitor;
    /** 当前车速（平滑后），0 表示静止。 */
    private float currentSpeedKmh;
    /** 是否有有效定位；无定位时标题栏用中性配色、隐藏车速。 */
    private boolean speedValid;
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
    private ImageButton wifiButton;
    private LrcView lrcView;
    private RotatingDiscView rotatingDiscView;
    private com.baidu.carplayer.widget.RotatingTextView rotatingTextView;
    private ParticleBackgroundView particleBackgroundView;
    private final com.baidu.carplayer.visualizer.VisualizerManager visualizerManager =
            new com.baidu.carplayer.visualizer.VisualizerManager();
    private com.baidu.carplayer.widget.VisualizerView visualizerView;
    private com.baidu.carplayer.widget.ParticleGlView particleGlView;
    private com.baidu.carplayer.widget.WaveGlView waveGlView;
    private com.baidu.carplayer.widget.ThunderGlView thunderGlView;
    private boolean glSupported;
    private final com.baidu.carplayer.visualizer.VisualizerCoordinator visualizerCoordinator =
            new com.baidu.carplayer.visualizer.VisualizerCoordinator(visualizerManager);
    private int visualizerMode;
    private boolean visualizerVisible;
    private static final int REQUEST_VISUALIZER_AUDIO = 401;
    private static final int REQUEST_LOCATION = 402;
    
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
    // 页面唯一的数据来源：Service 发布的不可变播放状态。
    private final PlaybackCoordinator.StateSubscriber playbackStateSubscriber =
            state -> this.handler.post(() -> renderPlaybackState(state));
    private PlayerViewState lastViewState;
    private final SeekGesture seekGesture = new SeekGesture();
    private boolean playbackStarted = false;
    private boolean pageStarted = false;
    private final Handler handler = new Handler(android.os.Looper.getMainLooper());
    
    // 已提示过的错误请求，避免同一错误在每次状态发布时重复弹出
    private long reportedErrorRequestId = -1L;

    // 数据管理
    private PlaylistManager playlistManager;
    private LyricsManager lyricsManager;
    private String playlistId;
    private String playlistName;
    private int currentPosition;
    private long songId; // 要播放的歌曲ID
    private boolean sortAscending = true; // 排序状态
    private boolean shuffleMode;
    // 歌词只由播放状态中的歌曲 ID 驱动；旧歌曲的结果会被丢弃。
    private final LyricsSession lyricsSession = new LyricsSession();
    private boolean volumeControlVisible = false;
    private final VolumeState volumeState = new VolumeState();
    
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
            audioPlayerService.subscribePlaybackState(playbackStateSubscriber);
            startPlaybackIfNeeded();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            audioPlayerService = null;
            visualizerCoordinator.setAudioSessionId(0);
            updateVisualization();
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
        shuffleMode = getIntent().getBooleanExtra("EXTRA_SHUFFLE_MODE", false);
        final boolean resumePlayback = getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false);

        // 如果是恢复播放模式，不需要 playlistId
        if (!resumePlayback && playlistId == null) {
            finish();
            return;
        }

        initViews();
        initData();
        
        // 标题栏/控制栏配色由车速驱动；无定位时用中性底色
        updateHeaderGradient();
        setupSpeedMonitor();
        
        bindPlayerService();
    }

    private void initViews() {
        songTitle = findViewById(R.id.song_title);
        songAlbum = findViewById(R.id.song_album);
        headerLayout = findViewById(R.id.header_layout);
        controlBar = findViewById(R.id.control_bar);
        speedText = findViewById(R.id.speed_text);
        speedUnit = findViewById(R.id.speed_unit);
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
        wifiButton = findViewById(R.id.wifi_button);
        lrcView = findViewById(R.id.lrc_view);
        rotatingDiscView = findViewById(R.id.rotating_disc);
        rotatingTextView = findViewById(R.id.rotating_text);
        particleBackgroundView = findViewById(R.id.particle_background);
        playlistButton = findViewById(R.id.playlist_button);
        visualizerView = findViewById(R.id.visualizer_view);
        visualizerView.attach(visualizerManager);
        particleGlView = findViewById(R.id.particle_gl_view);
        particleGlView.attach(visualizerManager);
        waveGlView = findViewById(R.id.wave_gl_view);
        waveGlView.attach(visualizerManager);
        thunderGlView = findViewById(R.id.thunder_gl_view);
        thunderGlView.attach(visualizerManager);
        glSupported = com.baidu.carplayer.widget.ParticleGlView.isSupported(this);
        String preferred = getSharedPreferences("player_visualizer", MODE_PRIVATE)
                .getString("mode", "default");
        String[] modes = com.baidu.carplayer.visualizer.VisualizerManager.MODES;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(preferred)) visualizerMode = i;
        }
        visualizerManager.switchTo(modes[visualizerMode]);
        findViewById(R.id.visualizer_button).setOnClickListener(v -> cycleVisualizer());
        updateVisualization();
        
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
        wifiButton.setOnClickListener(v -> showWifiDialog());
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
                if (fromUser) {
                    currentTime.setText(PlayerViewState.formatTime(progress));
                    if (lrcView != null) {
                        lrcView.updateTime(progress);
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seekGesture.start();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long songId = lastViewState != null ? lastViewState.songId : -1L;
                // 暂停状态下拖动也只发送 seek，不改变播放/暂停意图。
                PlaybackCommand seek = seekGesture.finish(songId, seekBar.getProgress());
                if (seek != null && serviceBound) {
                    audioPlayerService.dispatchCommand(seek);
                }
            }
        });

        // 音量条事件
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && serviceBound) {
                    float volume = progress / 100.0f;
                    audioPlayerService.dispatchCommand(PlaybackCommand.setVolume(volume));
                    // 如果用户拖动音量条，取消静音状态
                    boolean wasMuted = volumeState.isMuted();
                    volumeState.onUserVolume(volume);
                    if (wasMuted != volumeState.isMuted()) {
                        updateVolumeButton();
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
        if (!audioPlayerService.resumeSavedSession()) {
            Toast.makeText(this, "没有保存的播放状态", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /** 每个 Activity 实例只启动一次播放，配置变化/重新绑定不会重复加载。 */
    private void startPlaybackIfNeeded() {
        if (playbackStarted) {
            return;
        }
        playbackStarted = true;
        if (getIntent().getBooleanExtra(EXTRA_RESUME_PLAYBACK, false)) {
            resumePlaybackState();
        } else {
            loadPlaylistAndPlay();
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
                    if (isFinishing() || isDestroyed()) return;
                    if (songs.isEmpty()) {
                        Toast.makeText(PlayerActivity.this, "播放列表为空", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    // 应用与SongListActivity相同的排序规则
                    Song.sortSongs(songs, sortAscending);
                    if (serviceBound) {
                        audioPlayerService.startPlaylist(songs, songId, currentPosition, shuffleMode);
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
    private void handlePlaybackError(PlayerViewState state) {
        if (state.requestId == reportedErrorRequestId) {
            return;
        }
        reportedErrorRequestId = state.requestId;
        String message = state.errorMessage;
        boolean authExpired = message != null && message.contains("登录已失效");
        if (authExpired) {
            BaiduAuthService.getInstance(PlayerActivity.this).logout();
            Intent intent = new Intent(PlayerActivity.this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
        String errorMessage = "播放失败: " + (message != null ? message : "未知错误");
        Toast.makeText(PlayerActivity.this, errorMessage, Toast.LENGTH_LONG).show();
    }
    
    /**
     * 更新粒子背景状态
     */
    /**
     * 根据播放状态渲染整个播放页；所有 UI 更新只经过这里。
     */
    private void renderPlaybackState(PlaybackState state) {
        if (isDestroyed() || state == null) return;
        PlayerViewState view = PlayerViewState.from(state);
        PlayerViewState previous = lastViewState;
        lastViewState = view;

        if (view.song != null && view.songChangedFrom(previous)) {
            renderSongInfo(view.song);
        }
        if (view.songChangedFrom(previous) && pageStarted) {
            // 不可见时不加载歌词，onStart 会为当前歌曲补发请求。
            requestLyricsFor(view.song);
        }
        if (view.playingChangedFrom(previous)) {
            renderPlayPause(view.playing);
        }
        if (previous == null || previous.playMode != view.playMode) {
            renderPlayMode(view.playMode);
        }
        renderProgress(view);
        // 音频格式在播放器就绪后随状态发布，无需延迟轮询。
        if (view.audioInfoChangedFrom(previous)) {
            renderAudioInfo(view);
        }
        if (previous == null || previous.audioSessionId != view.audioSessionId
                || previous.playing != view.playing) {
            visualizerCoordinator.setAudioSessionId(view.audioSessionId);
            visualizerCoordinator.setPlaying(view.playing);
            updateVisualization();
        }
        if (view.error) {
            handlePlaybackError(view);
        }
    }

    private void renderSongInfo(Song song) {
        // 更新歌曲信息（使用去除扩展名的标题）
        String displayTitle = song.getTitleWithoutExtension();
        songTitle.setText(displayTitle);
        songAlbum.setText(song.getAlbum());
        if (rotatingDiscView != null) {
            rotatingDiscView.setTitle(displayTitle);
        }
        if (rotatingTextView != null) {
            rotatingTextView.setText(displayTitle);
        }
        updateTitleGradient();
    }

    private void requestLyricsFor(@Nullable Song song) {
        LyricsSession.Ticket ticket = lyricsSession.onSongChanged(song != null ? song.getId() : -1L);
        if (ticket == null && lyricsSession.getSongId() > 0) {
            return; // 同一首歌，保留已有歌词
        }
        lyricsManager.cancelPendingLoad();
        lrcView.setLrcEntries(null);
        if (ticket == null) {
            return;
        }
        lyricsManager.loadLyrics(song, new LyricsManager.OnLyricsLoadListener() {
            @Override
            public void onLyricsLoaded(List<LrcParser.LrcEntry> lrcEntries) {
                runOnUiThread(() -> {
                    if (!lyricsSession.accepts(ticket)) {
                        return;
                    }
                    lrcView.setLrcEntries(lrcEntries != null && !lrcEntries.isEmpty() ? lrcEntries : null);
                    if (lastViewState != null) {
                        lrcView.updateTime(lastViewState.positionMs);
                    }
                });
            }

            @Override
            public void onLyricsLoadFailed(String error) {
                runOnUiThread(() -> {
                    if (lyricsSession.accepts(ticket)) {
                        lrcView.setLrcEntries(null);
                    }
                });
            }
        });
    }

    private void togglePlayPause() {
        if (serviceBound) {
            // 出错后 Service 的 PLAY 会重新获取下载地址，页面无需区分。
            audioPlayerService.dispatch(PlaybackCommand.Type.TOGGLE);
        }
    }

    private void playPrevious() {
        if (serviceBound) {
            audioPlayerService.dispatch(PlaybackCommand.Type.PREVIOUS);
        }
    }

    private void playNext() {
        if (serviceBound) {
            audioPlayerService.dispatch(PlaybackCommand.Type.NEXT);
        }
    }

    private void togglePlayMode() {
        if (!serviceBound || lastViewState == null) return;
        AudioPlayerService.PlayMode nextMode = PlayModeCycle.next(lastViewState.playMode);
        Toast.makeText(this, PlayModeCycle.label(nextMode), Toast.LENGTH_SHORT).show();
        audioPlayerService.dispatchCommand(PlaybackCommand.setPlayMode(nextMode.ordinal()));
    }

    private void toggleVolumeControl() {
        volumeControlVisible = !volumeControlVisible;
        volumeBar.setVisibility(volumeControlVisible ? View.VISIBLE : View.GONE);

        if (volumeControlVisible && lastViewState != null) {
            // 初始化音量条位置
            volumeBar.setProgress((int) (lastViewState.volume * 100));
        }
    }
    
    /**
     * 切换静音状态
     */
    private void toggleMute() {
        if (!serviceBound) return;
        float current = lastViewState != null ? lastViewState.volume : 1f;
        float target = volumeState.toggle(current);
        audioPlayerService.dispatchCommand(PlaybackCommand.setVolume(target));
        volumeBar.setProgress((int) (target * 100));
        Toast.makeText(this, volumeState.isMuted() ? "已静音" : "已取消静音", Toast.LENGTH_SHORT).show();
        updateVolumeButton();
    }
    
    /**
     * 更新音量按钮图标
     */
    private void updateVolumeButton() {
        if (volumeState.isMuted()) {
            volumeButton.setImageResource(R.drawable.ic_volume_mute);
            volumeButton.setContentDescription("取消静音");
        } else {
            volumeButton.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
            volumeButton.setContentDescription("静音");
        }
    }

    /** Wi-Fi 图标：已连接常亮，未连接半透明。页面可见时每次刷新。 */
    private void updateWifiButton() {
        if (wifiButton == null) return;
        boolean connected = com.baidu.carplayer.network.HotspotManager.isWifiConnected(this);
        wifiButton.setAlpha(connected ? 1f : 0.45f);
        wifiButton.setContentDescription(connected ? "Wi-Fi 已连接" : "Wi-Fi 未连接，点击重连");
    }

    /**
     * Wi-Fi 对话框：显示当前状态，提供「重连热点」和「配置热点（系统自动重连）」。
     */
    private void showWifiDialog() {
        boolean connected = com.baidu.carplayer.network.HotspotManager.isWifiConnected(this);
        String ssid = com.baidu.carplayer.network.HotspotManager.currentSsid(this);
        String configured = com.baidu.carplayer.network.HotspotManager.loadSsid(this);
        StringBuilder msg = new StringBuilder();
        msg.append(connected ? "已连接" : "未连接");
        if (ssid != null) msg.append("  ·  ").append(ssid);
        msg.append(configured != null
                ? "\n已配置自动重连：" + configured
                : "\n尚未配置自动重连");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Wi-Fi / 热点")
                .setMessage(msg.toString())
                .setPositiveButton("重连热点", (d, w) -> reconnectHotspot(connected))
                .setNeutralButton("配置自动重连", (d, w) -> showConfigureDialog(configured))
                .setNegativeButton("关闭", null)
                .create();
        dialog.show();
        leftAlignButtons(dialog);
    }

    /** 对话框按钮栏默认右对齐；车载上从左扫视，改为左对齐。
     *  appcompat 的按钮栏里 neutral 和 negative/positive 之间有一个带权重的 Space，
     *  单设 gravity 不够，要把 Space 拿掉。 */
    private void leftAlignButtons(AlertDialog dialog) {
        View bar = dialog.findViewById(androidx.appcompat.R.id.buttonPanel);
        if (bar instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout panel = (android.widget.LinearLayout) bar;
            for (int i = panel.getChildCount() - 1; i >= 0; i--) {
                if (panel.getChildAt(i) instanceof android.widget.Space) {
                    panel.removeViewAt(i);
                }
            }
            panel.setGravity(android.view.Gravity.START);
        }
    }

    /**
     * 重连：先注册建议（档 2）+ reconnect（档 3），2.5 秒后检查是否连上，
     * 没连上就打开系统 Wi-Fi 面板兜底。
     */
    private void reconnectHotspot(boolean alreadyConnected) {
        if (alreadyConnected) {
            Toast.makeText(this, "当前已连接 Wi-Fi", Toast.LENGTH_SHORT).show();
            return;
        }
        String ssid = com.baidu.carplayer.network.HotspotManager.loadSsid(this);
        String pass = com.baidu.carplayer.network.HotspotManager.loadPass(this);
        if (ssid == null) {
            showConfigureDialog(null);
            return;
        }
        com.baidu.carplayer.network.HotspotManager.suggest(this, ssid, pass);
        com.baidu.carplayer.network.HotspotManager.tryReconnect(this);
        Toast.makeText(this, "正在重连 “" + ssid + "”…", Toast.LENGTH_SHORT).show();
        new Handler(getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (com.baidu.carplayer.network.HotspotManager.isWifiConnected(this)) {
                updateWifiButton();
            } else {
                Toast.makeText(this, "还未连上，已打开 Wi-Fi 面板", Toast.LENGTH_SHORT).show();
                com.baidu.carplayer.network.HotspotManager.openWifiPanel(this);
            }
        }, 2500);
    }

    /** 配置（或修改）自动重连的热点：SSID + 密码，存本地并注册给系统。 */
    /** 配置对话框的输入框：白字 18sp、浅灰 hint、圆角描边底框，深色底上看得清。 */
    private EditText styledInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x80ffffff);
        e.setTextColor(0xffffffff);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f);
        e.setSingleLine();
        e.setBackgroundResource(R.drawable.bg_dialog_input);
        int h = (int) (18 * getResources().getDisplayMetrics().density);
        int v = (int) (16 * getResources().getDisplayMetrics().density);
        e.setPadding(h, v, h, v);
        e.setTypeface(e.getTypeface(), android.graphics.Typeface.NORMAL);
        return e;
    }

    private void showConfigureDialog(String existingSsid) {
        EditText ssidInput = styledInput("热点名称（SSID）");
        ssidInput.setText(existingSsid == null ? "" : existingSsid);
        EditText passInput = styledInput("热点密码（8 位以上）");
        String oldPass = com.baidu.carplayer.network.HotspotManager.loadPass(this);
        passInput.setText(existingSsid != null && oldPass != null ? oldPass : "");
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(ssidInput);
        int gap = (int) (18 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = gap;
        box.addView(passInput, lp);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, (int) (pad * .5f), pad, 0);

        AlertDialog.Builder cfgBuilder = new AlertDialog.Builder(this)
                .setTitle("配置热点自动重连")
                .setMessage("填写手机热点的名称和密码。保存后系统会在热点范围内自动连接，无需 App 常驻。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String ssid = ssidInput.getText().toString().trim();
                    String pass = passInput.getText().toString();
                    String err = com.baidu.carplayer.network.HotspotManager.validate(ssid, pass);
                    if (err != null) {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                        return;
                    }
                    com.baidu.carplayer.network.HotspotManager.save(this, ssid, pass);
                    boolean ok = com.baidu.carplayer.network.HotspotManager.suggest(this, ssid, pass);
                    com.baidu.carplayer.network.HotspotManager.tryReconnect(this);
                    Toast.makeText(this, ok ? "已保存，系统会在热点范围内自动连接" : "已保存，但注册失败，请点“重连热点”试一次", Toast.LENGTH_LONG).show();
                    updateWifiButton();
                })
                .setNegativeButton("取消", null);
        AlertDialog cfgDialog = cfgBuilder.create();
        cfgDialog.show();
        leftAlignButtons(cfgDialog);
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
        currentPlaylistAdapter.setOnSongClickListener(songId -> {
            if (serviceBound && audioPlayerService != null) {
                audioPlayerService.dispatchCommand(PlaybackCommand.select(0L, songId, null, true));
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
        PlayerViewState state = lastViewState;
        List<Song> queue = state != null ? state.queue : null;
        if (queue == null || queue.isEmpty()) {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
            return;
        }
        currentPlaylistAdapter.setSongs(queue);
        currentPlaylistAdapter.setCurrentPlayingSongId(state.songId);
        playlistContainer.setVisibility(View.VISIBLE);
        playlistVisible = true;
        // 清空搜索框
        playlistSearchInput.setText("");
        int index = state.currentIndex;
        if (index >= 0 && index < queue.size()) {
            playlistRecyclerView.scrollToPosition(index);
        }
    }
    
    /**
     * 隐藏播放列表
     */
    private void hidePlaylist() {
        playlistContainer.setVisibility(View.GONE);
        playlistVisible = false;
    }

    private void renderPlayPause(boolean playing) {
        if (playing) {
            playPauseButton.setImageResource(R.drawable.ic_pause);
            playPauseButton.setContentDescription("暂停");
            if (rotatingDiscView != null) rotatingDiscView.resumeRotation();
            if (rotatingTextView != null) rotatingTextView.resumeRotation();
            startShimmerAnimation();
        } else {
            playPauseButton.setImageResource(R.drawable.ic_play);
            playPauseButton.setContentDescription("播放");
            if (rotatingDiscView != null) rotatingDiscView.pauseRotation();
            if (rotatingTextView != null) rotatingTextView.pauseRotation();
            stopShimmerAnimation();
        }
    }

    private void renderPlayMode(int modeOrdinal) {
        AudioPlayerService.PlayMode mode = PlayModeCycle.fromOrdinal(modeOrdinal);
        switch (mode) {
            case RANDOM:
                playModeButton.setImageResource(R.drawable.ic_shuffle);
                playModeButton.setContentDescription("随机播放");
                break;
            case SINGLE:
                playModeButton.setImageResource(R.drawable.ic_repeat_one);
                playModeButton.setContentDescription("单曲循环");
                break;
            case ORDER:
            default:
                playModeButton.setImageResource(R.drawable.ic_repeat);
                playModeButton.setContentDescription("顺序播放");
                break;
        }
    }

    private void renderProgress(PlayerViewState view) {
        totalTime.setText(PlayerViewState.formatTime(view.durationMs));
        long position = seekGesture.displayPosition(view);
        if (position < 0) {
            return; // 拖动中不覆盖用户正在选择的位置
        }
        if (view.durationMs > 0) {
            progressBar.setMax((int) view.durationMs);
            progressBar.setProgress((int) position);
        } else {
            progressBar.setProgress(0);
        }
        currentTime.setText(PlayerViewState.formatTime(position));
        if (lrcView != null) {
            lrcView.updateTime(position);
        }
    }

    @Override
    protected void onDestroy() {
        lyricsSession.close();
        if (lyricsManager != null) {
            lyricsManager.cancelPendingLoad();
        }
        visualizerCoordinator.close();
        if (visualizerView != null) visualizerView.setActive(false);
        if (particleGlView != null) particleGlView.setActive(false);
        if (waveGlView != null) waveGlView.setActive(false);
        if (thunderGlView != null) thunderGlView.setActive(false);
        if (speedMonitor != null) { speedMonitor.stop(); speedMonitor = null; }
        if (serviceBound) {
            audioPlayerService.unsubscribePlaybackState(playbackStateSubscriber);
            unbindService(serviceConnection);
            serviceBound = false;
        }
        handler.removeCallbacksAndMessages(null);
        // 停止流光动画
        stopShimmerAnimation();
        super.onDestroy();
    }
    
    /**
     * 拦截方向盘媒体按键事件，转发给 AudioPlayerService
     * 确保在播放页前台时，方向盘按键能正常控制播放
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        AudioPlayerService service = serviceBound ? audioPlayerService : null;
        if (MediaKeyDispatcher.dispatch(event, service, null)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    
    /**
     * 渲染音频信息行（格式 | 采样率 | 比特率 | 大小 | 曲目数），只读取已发布状态。
     */
    private void renderAudioInfo(PlayerViewState view) {
        if (audioInfo == null || view.song == null) return;
        Song song = view.song;
        StringBuilder info = new StringBuilder();
        AudioFormat format = view.audioFormat;
        String formatName = format != null ? format.displayName() : null;
        if (formatName != null) {
            info.append(formatName);
            if (format.sampleRate > 0) {
                info.append(" | ").append(format.sampleRate / 1000).append("kHz");
            }
            if (format.bitrate > 0) {
                info.append(" | ").append(format.bitrate / 1000).append("kbps");
            }
        } else {
            // 如果无法获取格式信息，尝试使用文件扩展名
            String extension = song.getExtension();
            info.append(!extension.isEmpty() ? extension : "--");
        }
        long size = song.getSize();
        if (size > 0) {
            info.append(" | ").append(formatFileSize(size));
        }
        if (view.queueSize > 0) {
            info.append(" | 共").append(view.queueSize).append("首");
        }
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
    /**
     * 按车速重绘标题栏渐变与底部控制栏压暗色：静止靛蓝、巡航青蓝、高速转琥珀/橙红。
     * 无有效定位时退回中性深色，不显示车速。
     */
    private void updateHeaderGradient() {
        if (headerLayout == null) return;
        float kmh = speedValid ? currentSpeedKmh : 0f;
        int[] colors = com.baidu.carplayer.vehicle.SpeedTheme.headerColors(kmh, speedValid);
        headerLayout.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{colors[0], colors[1]}));
        if (controlBar != null) {
            // 保持"透明 → 压暗"的原有结构，只把压暗色换成随车速变化的色调
            controlBar.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.TRANSPARENT, colors[2]}));
        }
    }

    /**
     * 应用一次车速读数：更新数字、按车速重绘标题栏与控制栏配色。
     * 静止（<1km/h）或无有效定位时隐藏数字，配色退回中性并把标题栏压暗一档。
     */
    void applySpeed(float kmh, boolean valid) {
        currentSpeedKmh = kmh;
        speedValid = valid;
        if (speedText != null) {
            boolean show = valid && kmh >= 1f;
            speedText.setVisibility(show ? View.VISIBLE : View.GONE);
            if (speedUnit != null) {
                speedUnit.setVisibility(show ? View.VISIBLE : View.GONE);
                // 单位跟随数字的强调色，但淡一档，保持层级
                speedUnit.setTextColor((com.baidu.carplayer.vehicle.SpeedTheme.accent(kmh)
                        & 0x00ffffff) | 0xb0000000);
            }
            if (show) {
                // 四舍五入：87.6 应显示 88，而不是被截断成 87
                boolean first = speedText.getText().isEmpty();
                if (first) {
                    // 数字尺寸只设一次，保证和标题栏协调
                    float sp = getResources().getDisplayMetrics().scaledDensity;
                    speedText.setSize(sp * 2.6f, sp * 8.5f, sp * 17f, sp * 4.2f);
                }
                speedText.setDigitColor(com.baidu.carplayer.vehicle.SpeedTheme.accent(kmh));
                // 首帧直接显示，之后像里程表一样滚动到新车速
                speedText.setTextNumber(Math.round(kmh), !first);
                speedText.setDigitColor(com.baidu.carplayer.vehicle.SpeedTheme.accent(kmh));
            }
        }
        updateHeaderGradient();
    }

    /** 启动车速显示：申请定位权限后开始接收 GPS 地速。 */
    private void setupSpeedMonitor() {
        if (speedMonitor != null) return;
        speedMonitor = new com.baidu.carplayer.vehicle.SpeedMonitor(this);
        speedMonitor.setListener(this::applySpeed);
        if (com.baidu.carplayer.vehicle.SpeedMonitor.hasPermission(this)) {
            speedMonitor.start();
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
        }
    }

    private void cycleVisualizer() {
        visualizerMode = (visualizerMode + 1) % com.baidu.carplayer.visualizer.VisualizerManager.MODES.length;
        String mode = com.baidu.carplayer.visualizer.VisualizerManager.MODES[visualizerMode];
        visualizerManager.switchTo(mode);
        getSharedPreferences("player_visualizer", MODE_PRIVATE).edit().putString("mode", mode).apply();
        if (visualizerMode != 0 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "音频可视化需要音频采集权限，仅分析当前播放会话，不保存录音", Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_VISUALIZER_AUDIO);
        }
        updateVisualization();
    }

    private void updateVisualization() {
        if (visualizerView == null) return;
        boolean permitted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        visualizerCoordinator.setModeEnabled(visualizerMode != 0);
        visualizerCoordinator.setPermissionGranted(permitted);
        visualizerCoordinator.setScreenVisible(visualizerVisible && serviceBound);
        boolean show = visualizerCoordinator.apply();
        boolean playing = visualizerVisible && lastViewState != null && lastViewState.playing;
        String selectedMode = com.baidu.carplayer.visualizer.VisualizerManager.MODES[visualizerMode];
        boolean useParticleGl = show && glSupported && "particles".equals(selectedMode);
        boolean useWaveGl = show && glSupported && "wave".equals(selectedMode);
        boolean useThunderGl = show && glSupported && "thunder".equals(selectedMode);
        boolean useGl = useParticleGl || useWaveGl || useThunderGl;
        visualizerView.setVisibility(show && !useGl ? View.VISIBLE : View.GONE);
        particleGlView.setVisibility(useParticleGl ? View.VISIBLE : View.GONE);
        waveGlView.setVisibility(useWaveGl ? View.VISIBLE : View.GONE);
        thunderGlView.setVisibility(useThunderGl ? View.VISIBLE : View.GONE);
        rotatingDiscView.setVisibility(show ? View.GONE : View.VISIBLE);
        rotatingTextView.setVisibility(show ? View.GONE : View.VISIBLE);
        particleBackgroundView.setVisibility(show ? View.GONE : View.VISIBLE);
        visualizerView.setActive(!useGl && visualizerCoordinator.isRendering());
        particleGlView.setActive(useParticleGl && visualizerCoordinator.isRendering());
        waveGlView.setActive(useWaveGl && visualizerCoordinator.isRendering());
        thunderGlView.setActive(useThunderGl && visualizerCoordinator.isRendering());
        particleBackgroundView.setPlaying(!show && playing);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            // 拒绝也不影响播放：车速隐藏、配色退回中性
            if (com.baidu.carplayer.vehicle.SpeedMonitor.hasPermission(this) && speedMonitor != null) {
                speedMonitor.start();
            }
            return;
        }
        if (requestCode == REQUEST_VISUALIZER_AUDIO) {
            if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "未授予权限，继续使用唱片模式，不影响播放", Toast.LENGTH_SHORT).show();
            }
            updateVisualization();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visualizerVisible = true;
        updateVisualization();
        // 页面可见才接收定位（导航/听歌时才有意义），避免后台耗电
        if (speedMonitor != null && com.baidu.carplayer.vehicle.SpeedMonitor.hasPermission(this)) {
            speedMonitor.start();
        }
        updateWifiButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speedMonitor != null) speedMonitor.stop();
        // 当Activity进入后台时保存播放进度
        if (serviceBound && audioPlayerService != null) {
            audioPlayerService.dispatch(PlaybackCommand.Type.SAVE_STATE);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        pageStarted = false;
        seekGesture.cancel();
        visualizerVisible = false;
        updateVisualization();
        // 不可见时取消歌词加载；重新可见时会根据当前歌曲重新请求。
        lyricsSession.invalidate();
        if (lyricsManager != null) {
            lyricsManager.cancelPendingLoad();
        }
        // 当Activity完全不可见时再次保存播放进度（双重保险）
        if (serviceBound && audioPlayerService != null) {
            audioPlayerService.dispatch(PlaybackCommand.Type.SAVE_STATE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        pageStarted = true;
        if (lastViewState != null && lastViewState.song != null) {
            requestLyricsFor(lastViewState.song);
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
            if (lastViewState != null) {
                Song currentSong = lastViewState.song;
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