package com.baidu.carplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.PlaylistGridAdapter;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.service.AudioPlayerService;
import com.google.android.material.textfield.TextInputEditText;
import androidx.media3.common.PlaybackException;

import java.util.List;

/**
 * 主页面 - 播放列表管理页面（矩阵网格布局）
 */
public class MainActivity extends AppCompatActivity implements PlaylistGridAdapter.OnPlaylistClickListener {

    private RecyclerView playlistGridView;
    private PlaylistGridAdapter playlistAdapter;
    private PlaylistManager playlistManager;
    private ImageButton addPlaylistButton;
    private ImageButton nowPlayingButton;
    private View emptyState;

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
        setContentView(R.layout.activity_main);

        initViews();
        initData();
        
        // 绑定服务
        Intent intent = new Intent(this, AudioPlayerService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        // 每次Activity可见时检查播放状态
        checkNowPlayingStatus();
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
        playlistGridView = findViewById(R.id.playlist_grid_view);
        addPlaylistButton = findViewById(R.id.add_playlist_button);
        nowPlayingButton = findViewById(R.id.now_playing_button);
        emptyState = findViewById(R.id.empty_state);

        // 设置网格布局（3列）
        GridLayoutManager gridLayoutManager = new GridLayoutManager(this, 3);
        playlistGridView.setLayoutManager(gridLayoutManager);

        // 设置适配器
        playlistAdapter = new PlaylistGridAdapter();
        playlistAdapter.setOnPlaylistClickListener(this);
        playlistGridView.setAdapter(playlistAdapter);

        // 设置按钮点击事件
        addPlaylistButton.setOnClickListener(v -> showCreatePlaylistDialog());
        nowPlayingButton.setOnClickListener(v -> openCurrentPlayer());
        
        findViewById(R.id.add_playlist_empty_button).setOnClickListener(v -> showCreatePlaylistDialog());
    }
    
    private void checkNowPlayingStatus() {
        if (serviceBound && audioPlayerService != null) {
            // 检查是否有正在播放的歌曲或者保存的播放状态
            if (audioPlayerService.isPlaying() || audioPlayerService.getCurrentSong() != null
                    || audioPlayerService.hasSavedPlaybackState()) {
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
        intent.putExtra(PlayerActivity.EXTRA_RESUME_PLAYBACK, true);
        startActivity(intent);
    }

    private void initData() {
        playlistManager = new PlaylistManager(this);
        loadPlaylists();
    }

    private void loadPlaylists() {
        playlistManager.getAllPlaylists(new PlaylistManager.OnPlaylistsLoadListener() {
            @Override
            public void onSuccess(List<Playlist> playlists) {
                runOnUiThread(() -> {
                    playlistAdapter.setPlaylists(playlists);
                    
                    // 显示或隐藏空状态
                    if (playlists.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        playlistGridView.setVisibility(View.GONE);
                    } else {
                        emptyState.setVisibility(View.GONE);
                        playlistGridView.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载播放列表失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showCreatePlaylistDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_playlist, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.playlist_name_input);

        new AlertDialog.Builder(this)
                .setTitle("创建播放列表")
                .setView(dialogView)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入播放列表名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createPlaylist(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createPlaylist(String name) {
        playlistManager.createPlaylist(name, new PlaylistManager.OnResultListener() {
            @Override
            public void onSuccess(Object result) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "播放列表已创建", Toast.LENGTH_SHORT).show();
                    loadPlaylists();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "创建失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void openSettings() {
        // TODO: 打开设置页面
        Toast.makeText(this, "设置功能开发中...", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPlaylistClick(Playlist playlist) {
        // 打开歌曲列表页面
        Intent intent = new Intent(this, SongListActivity.class);
        intent.putExtra("playlist_id", playlist.getId());
        intent.putExtra("playlist_name", playlist.getName());
        startActivity(intent);
    }

    @Override
    public void onPlaylistLongClick(Playlist playlist) {
        showPlaylistOptionsDialog(playlist);
    }

    private void showPlaylistOptionsDialog(Playlist playlist) {
        String[] options = {"重命名", "删除"};
        
        new AlertDialog.Builder(this)
                .setTitle(playlist.getName())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // 重命名
                            showRenamePlaylistDialog(playlist);
                            break;
                        case 1: // 删除
                            showDeleteConfirmDialog(playlist);
                            break;
                    }
                })
                .show();
    }

    private void showRenamePlaylistDialog(Playlist playlist) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_playlist, null);
        TextInputEditText nameInput = dialogView.findViewById(R.id.playlist_name_input);
        nameInput.setText(playlist.getName());

        new AlertDialog.Builder(this)
                .setTitle("重命名播放列表")
                .setView(dialogView)
                .setPositiveButton("确定", (dialog, which) -> {
                    String newName = nameInput.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "请输入播放列表名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    renamePlaylist(playlist, newName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void renamePlaylist(Playlist playlist, String newName) {
        playlist.setName(newName);
        playlistManager.updatePlaylist(playlist, new PlaylistManager.OnResultListener() {
            @Override
            public void onSuccess(Object result) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "已重命名", Toast.LENGTH_SHORT).show();
                    loadPlaylists();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "重命名失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showDeleteConfirmDialog(Playlist playlist) {
        new AlertDialog.Builder(this)
                .setTitle("删除播放列表")
                .setMessage("确定要删除播放列表 \"" + playlist.getName() + "\" 吗？")
                .setPositiveButton("删除", (dialog, which) -> deletePlaylist(playlist))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deletePlaylist(Playlist playlist) {
        playlistManager.deletePlaylist(playlist.getId(), new PlaylistManager.OnResultListener() {
            @Override
            public void onSuccess(Object result) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    loadPlaylists();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "删除失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新播放列表（可能在其他页面修改了）
        loadPlaylists();
    }
}