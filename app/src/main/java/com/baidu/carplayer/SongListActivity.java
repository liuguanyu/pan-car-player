package com.baidu.carplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.SongAdapter;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.service.AudioPlayerService;
import androidx.media3.common.PlaybackException;

import java.util.List;

/**
 * 歌曲列表页面
 */
public class SongListActivity extends AppCompatActivity implements SongAdapter.OnSongClickListener {

    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    public static final String EXTRA_PLAYLIST_NAME = "playlist_name";

    private RecyclerView songRecyclerView;
    private SongAdapter songAdapter;
    private PlaylistManager playlistManager;
    private View emptyState;
    private TextView playlistTitle;
    private ImageButton backButton;
    private ImageButton addSongsButton;
    private ImageButton playAllButton;
    private ImageButton shuffleButton;
    private ImageButton nowPlayingButton;

    private String playlistId;
    private String playlistName;
    private List<Song> currentSongs;

    private AudioPlayerService audioPlayerService;
    private boolean serviceBound = false;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            audioPlayerService = binder.getService();
            serviceBound = true;
            checkNowPlayingStatus();
            
            // 监听播放状态变化
            audioPlayerService.setPlaybackStateListener(new AudioPlayerService.PlaybackStateListener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    checkNowPlayingStatus();
                }
                
                @Override
                public void onPlayWhenReadyChanged(boolean playWhenReady) {
                    checkNowPlayingStatus();
                }
                
                @Override
                public void onPlayerError(PlaybackException error) {}
                
                @Override
                public void onPositionDiscontinuity() {
                    checkNowPlayingStatus();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_song_list);

        // 获取传入的参数
        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        playlistName = getIntent().getStringExtra(EXTRA_PLAYLIST_NAME);
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (playlistId == null) {
            finish();
            return;
        }

        initData();
        initViews();
        loadSongs();
        
        // 绑定服务（服务连接成功后会自动调用checkNowPlayingStatus）
        Intent intent = new Intent(this, AudioPlayerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    private void initViews() {
        songRecyclerView = findViewById(R.id.song_recycler_view);
        emptyState = findViewById(R.id.empty_state);
        playlistTitle = findViewById(R.id.playlist_title);
        backButton = findViewById(R.id.back_button);
        addSongsButton = findViewById(R.id.add_songs_button);
        playAllButton = findViewById(R.id.play_all_button);
        shuffleButton = findViewById(R.id.shuffle_button);
        nowPlayingButton = findViewById(R.id.now_playing_button);

        // 设置标题
        if (playlistName != null) {
            playlistTitle.setText(playlistName);
        } else {
            // 如果名称为空，尝试从数据库加载
            loadPlaylistInfo();
        }

        // 设置RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        songRecyclerView.setLayoutManager(layoutManager);

        songAdapter = new SongAdapter();
        songAdapter.setOnSongClickListener(this);
        songRecyclerView.setAdapter(songAdapter);

        // 设置按钮点击事件
        backButton.setOnClickListener(v -> onBackPressed());
        addSongsButton.setOnClickListener(v -> openFileBrowser());
        playAllButton.setOnClickListener(v -> playAll());
        shuffleButton.setOnClickListener(v -> shufflePlay());
        nowPlayingButton.setOnClickListener(v -> openCurrentPlayer());
        
        findViewById(R.id.add_songs_empty_button).setOnClickListener(v -> openFileBrowser());
    }
    
    private void checkNowPlayingStatus() {
        if (serviceBound && audioPlayerService != null) {
            // 只有在播放进行中时才显示按钮
            if (audioPlayerService.isPlaying()) {
                nowPlayingButton.setVisibility(View.VISIBLE);
            } else {
                nowPlayingButton.setVisibility(View.GONE);
            }
        } else {
            nowPlayingButton.setVisibility(View.GONE);
        }
    }
    
    private void openCurrentPlayer() {
        // 打开播放器，不传递position，让PlayerActivity恢复当前状态
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, playlistId);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_NAME, playlistName);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_PLAYBACK, true);
        startActivity(intent);
    }

    private void initData() {
        playlistManager = new PlaylistManager(this);
    }

    private void loadSongs() {
        playlistManager.getSongsForPlaylist(playlistId, new PlaylistManager.OnSongsLoadListener() {
            @Override
            public void onSuccess(List<Song> songs) {
                runOnUiThread(() -> {
                    currentSongs = songs;
                    songAdapter.setSongs(songs);
                    
                    // 显示或隐藏空状态
                    if (songs.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        songRecyclerView.setVisibility(View.GONE);
                    } else {
                        emptyState.setVisibility(View.GONE);
                        songRecyclerView.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(SongListActivity.this, "加载歌曲失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadPlaylistInfo() {
        playlistManager.getPlaylist(playlistId, new PlaylistManager.OnPlaylistLoadListener() {
            @Override
            public void onSuccess(com.baidu.carplayer.model.Playlist playlist) {
                runOnUiThread(() -> {
                    if (playlist != null) {
                        playlistName = playlist.getName();
                        playlistTitle.setText(playlistName);
                    }
                });
            }

            @Override
            public void onError(String error) {
                // 忽略错误，可能播放列表已被删除
            }
        });
    }

    @Override
    public void onBackPressed() {
        // 检查是否是任务栈的根Activity
        if (isTaskRoot()) {
            // 如果是根Activity，导航到MainActivity而不是退出应用
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else {
            // 否则正常返回
            super.onBackPressed();
        }
    }

    private void openFileBrowser() {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_PLAYLIST_ID, playlistId);
        startActivity(intent);
    }

    private void playAll() {
        if (currentSongs == null || currentSongs.isEmpty()) {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 打开播放页面并播放第一首
        openPlayerActivity(0);
    }

    private void shufflePlay() {
        if (currentSongs == null || currentSongs.isEmpty()) {
            Toast.makeText(this, "播放列表为空", Toast.LENGTH_SHORT).show();
            return;
        }

       // 随机选择一首歌曲开始播放
        int randomPosition = (int) (Math.random() * currentSongs.size());
        openPlayerActivity(randomPosition, true);
    }

    private void openPlayerActivity(int position) {
        openPlayerActivity(position, false);
    }

    private void openPlayerActivity(int position, boolean shuffle) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_ID, playlistId);
        intent.putExtra(PlayerActivity.EXTRA_PLAYLIST_NAME, playlistName);
        intent.putExtra(PlayerActivity.EXTRA_POSITION, position);
        if (shuffle) {
            intent.putExtra("EXTRA_SHUFFLE_MODE", true);
        }
        startActivity(intent);
    }

    @Override
    public void onSongClick(Song song, int position) {
        // 打开播放页面
        openPlayerActivity(position);
    }

    @Override
    public void onPlayFromHereClick(Song song, int position) {
        // 从这首歌曲开始播放
        openPlayerActivity(position);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新歌曲列表
        loadSongs();
        // 检查播放状态（确保每次恢复时都能正确显示/隐藏按钮）
        checkNowPlayingStatus();
    }
}