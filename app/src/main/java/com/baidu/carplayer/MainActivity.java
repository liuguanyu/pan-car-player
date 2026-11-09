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
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.PlaylistAdapter;
import com.baidu.carplayer.backup.PlaylistTransferController;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.ui.LoadGate;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.repository.CloudFileRepository;
import com.baidu.carplayer.repository.Repositories;
import com.baidu.carplayer.playback.MediaKeyDispatcher;
import com.baidu.carplayer.playback.PlaybackCoordinator;
import com.baidu.carplayer.playback.PlaybackState;
import com.baidu.carplayer.service.AudioPlayerService;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

/**
 * 主页面 - 播放列表管理页面（矩阵网格布局）
 */
public class MainActivity extends AppCompatActivity implements PlaylistAdapter.OnPlaylistClickListener {

    private RecyclerView playlistListView;
    private PlaylistAdapter playlistAdapter;
    private PlaylistManager playlistManager;
    private ImageButton addPlaylistButton;
    private ImageButton nowPlayingButton;
    private View emptyState;

    private AudioPlayerService audioPlayerService;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // “正在播放”按钮只由 Service 发布的播放状态驱动。
    private final PlaybackCoordinator.StateSubscriber playbackStateSubscriber = state -> {
        boolean playing = state.getStatus() == PlaybackState.Status.READY && state.isPlayWhenReady();
        mainHandler.post(() -> {
            lastKnownPlaying = playing;
            renderNowPlaying();
        });
    };
    private boolean lastKnownPlaying = false;
    private boolean serviceBound = false;
    
    private CloudFileRepository cloudFiles;
    private PlaylistTransferController transfer;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            audioPlayerService = binder.getService();
            serviceBound = true;
            audioPlayerService.subscribePlaybackState(playbackStateSubscriber);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            audioPlayerService = null;
            lastKnownPlaying = false;
            renderNowPlaying();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initData();
        // 必须在 onCreate 中创建：内部注册页面结果回调
        transfer = new PlaylistTransferController(this, playlistManager, this::loadPlaylists);
        
        // 服务绑定放在 onStart，与 onStop 配对。
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        if (!serviceBound) {
            Intent intent = new Intent(this, AudioPlayerService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
        renderNowPlaying();
    }

    @Override
    protected void onStop() {
        loadGate.cancel();
        if (serviceBound) {
            if (audioPlayerService != null) {
                audioPlayerService.unsubscribePlaybackState(playbackStateSubscriber);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onStop();
    }
    
    /**
     * 拦截方向盘媒体按键事件，转发给 AudioPlayerService
     * 确保在主页面时，方向盘按键能正常控制播放
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        AudioPlayerService service = serviceBound ? audioPlayerService : null;
        if (MediaKeyDispatcher.dispatch(event, service, null)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void initViews() {
        playlistListView = findViewById(R.id.playlist_list_view);
        addPlaylistButton = findViewById(R.id.add_playlist_button);
        nowPlayingButton = findViewById(R.id.now_playing_button);
        emptyState = findViewById(R.id.empty_state);

        // 设置列表布局
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        playlistListView.setLayoutManager(layoutManager);

        // 设置适配器
        playlistAdapter = new PlaylistAdapter();
        playlistAdapter.setOnPlaylistClickListener(this);
        playlistListView.setAdapter(playlistAdapter);

        // 设置按钮点击事件
        addPlaylistButton.setOnClickListener(v -> showCreatePlaylistDialog());
        nowPlayingButton.setOnClickListener(v -> openCurrentPlayer());
        findViewById(R.id.transfer_button).setOnClickListener(v -> transfer.showMenu());
        
        findViewById(R.id.add_playlist_empty_button).setOnClickListener(v -> showCreatePlaylistDialog());
    }
    
    private boolean isNowPlayingSuppressed() {
        return false;
    }

    private void renderNowPlaying() {
        if (isDestroyed() || nowPlayingButton == null) return;
        nowPlayingButton.setVisibility(serviceBound && lastKnownPlaying && !isNowPlayingSuppressed()
                ? View.VISIBLE : View.GONE);
    }
    
    private void openCurrentPlayer() {
        // 打开播放器，不传递position，让PlayerActivity恢复当前状态
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_RESUME_PLAYBACK, true);
        startActivity(intent);
    }

    // 只允许一次在途加载，避免 onStart/onResume 重复请求。
    private final LoadGate loadGate = new LoadGate();

    private void initData() {
        playlistManager = new PlaylistManager(this);
        cloudFiles = Repositories.cloudFiles(this);
    }

    private void loadPlaylists() {
        final long token = loadGate.begin();
        if (token < 0) {
            return;
        }
        playlistManager.getAllPlaylists(new PlaylistManager.OnPlaylistsLoadListener() {
            @Override
            public void onSuccess(List<Playlist> playlists) {
                runOnUiThread(() -> {
                    if (!loadGate.isCurrent(token)) return;
                    loadGate.finish(token);
                    playlistAdapter.setPlaylists(playlists);
                    
                    // 显示或隐藏空状态
                    if (playlists.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        playlistListView.setVisibility(View.GONE);
                    } else {
                        emptyState.setVisibility(View.GONE);
                        playlistListView.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!loadGate.isCurrent(token)) return;
                    loadGate.finish(token);
                    Toast.makeText(MainActivity.this, "加载播放列表失败: " + error, Toast.LENGTH_SHORT).show();
                });
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

                    // 直接跳转到文件浏览器添加歌曲
                    if (result instanceof Playlist) {
                        Playlist newPlaylist = (Playlist) result;
                        openFileBrowser(newPlaylist.getId());
                    }
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
    public void onPlaylistClick(Playlist playlist, int position) {
        // 打开歌曲列表页面
        Intent intent = new Intent(this, SongListActivity.class);
        intent.putExtra("playlist_id", playlist.getId());
        intent.putExtra("playlist_name", playlist.getName());
        startActivity(intent);
    }

    @Override
    public void onPlaylistMoreClick(Playlist playlist, int position) {
        showPlaylistOptionsDialog(playlist);
    }
    
    @Override
    public void onPlaylistLongClick(Playlist playlist, int position) {
        showPlaylistOptionsDialog(playlist);
    }

    private void showPlaylistOptionsDialog(Playlist playlist) {
        String[] options = {"刷新", "重命名", "删除"};
        
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.select_dialog_item, options) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = (TextView) view.findViewById(android.R.id.text1);
                if (textView != null) {
                    textView.setTextColor(getColor(R.color.car_text_primary));
                }
                return view;
            }
        };
        
        new AlertDialog.Builder(this)
                .setTitle(playlist.getName())
                .setAdapter(adapter, (dialog, which) -> {
                    switch (which) {
                        case 0: // 刷新
                            refreshPlaylist(playlist);
                            break;
                        case 1: // 重命名
                            showRenamePlaylistDialog(playlist);
                            break;
                        case 2: // 删除
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

    private void openFileBrowser(String playlistId) {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_PLAYLIST_ID, playlistId);
        startActivity(intent);
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
    
    private void refreshPlaylist(Playlist playlist) {
        if (!cloudFiles.hasToken()) {
            Toast.makeText(this, "未登录，请先登录", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (playlist.getId() == null) {
            Toast.makeText(this, "播放列表ID为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示进度对话框
        android.app.ProgressDialog progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在刷新列表...");
        progressDialog.setCancelable(false);
        progressDialog.show();
        
        // 调用PlaylistManager的刷新方法
        playlistManager.refreshPlaylist(playlist.getId(), cloudFiles, new PlaylistManager.OnResultListener() {
            @Override
            public void onSuccess(Object result) {
                runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, (String) result, Toast.LENGTH_LONG).show();
                    // 刷新播放列表显示
                    loadPlaylists();
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(MainActivity.this, "刷新失败: " + error, Toast.LENGTH_LONG).show();
                });
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