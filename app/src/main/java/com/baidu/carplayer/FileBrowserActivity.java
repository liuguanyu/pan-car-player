package com.baidu.carplayer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.FileAdapter;
import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.AuthInfo;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.FileListResponse;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 文件浏览器Activity - 从百度网盘选择音频文件
 */
public class FileBrowserActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener {

    public static final String EXTRA_PLAYLIST_ID = "playlist_id";

    private RecyclerView fileRecyclerView;
    private FileAdapter fileAdapter;
    private TextView currentPathText;
    private TextView selectedCountText;
    private ImageButton backButton;
    private MaterialButton selectAllButton;
    private MaterialButton addButton;
    private MaterialButton clearSelectionButton;
    private MaterialButton refreshButton;
    private View selectionInfo;
    private View loadingLayout;
    private View emptyLayout;

    private String playlistId;
    private String currentPath = "/";
    private List<FileItem> currentFiles = new ArrayList<>();
    private PlaylistManager playlistManager;
    private BaiduPanService baiduPanService;
    private String accessToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        if (playlistId == null) {
            Toast.makeText(this, "播放列表ID为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        initData();
        loadFiles();
    }

    private void initViews() {
        fileRecyclerView = findViewById(R.id.file_recycler_view);
        currentPathText = findViewById(R.id.current_path);
        selectedCountText = findViewById(R.id.selected_count);
        backButton = findViewById(R.id.back_button);
        selectAllButton = findViewById(R.id.select_all_button);
        addButton = findViewById(R.id.add_button);
        clearSelectionButton = findViewById(R.id.clear_selection_button);
        refreshButton = findViewById(R.id.refresh_button);
        selectionInfo = findViewById(R.id.selection_info);
        loadingLayout = findViewById(R.id.loading_layout);
        emptyLayout = findViewById(R.id.empty_layout);

        // 设置RecyclerView
        fileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileAdapter = new FileAdapter();
        fileAdapter.setOnFileClickListener(this);
        fileRecyclerView.setAdapter(fileAdapter);

        // 设置按钮点击事件
        backButton.setOnClickListener(v -> navigateUp());
        selectAllButton.setOnClickListener(v -> toggleSelectAll());
        addButton.setOnClickListener(v -> addSelectedFiles());
        clearSelectionButton.setOnClickListener(v -> clearSelection());
        refreshButton.setOnClickListener(v -> loadFiles());
    }

    private void initData() {
        playlistManager = new PlaylistManager(this);
        baiduPanService = RetrofitClient.getInstance().create(BaiduPanService.class);

        // 获取访问令牌
        AuthInfo authInfo = BaiduAuthService.getInstance(this).getAuthInfo();
        if (authInfo != null) {
            accessToken = authInfo.getAccessToken();
        } else {
            Toast.makeText(this, "未登录，请先登录", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadFiles() {
        if (accessToken == null) {
            return;
        }

        showLoading();

        Call<FileListResponse> call = baiduPanService.getFileList(
                "list",
                accessToken,
                currentPath,
                "name",
                0,
                1000,
                1,
                0,
                0
        );

        call.enqueue(new Callback<FileListResponse>() {
            @Override
            public void onResponse(Call<FileListResponse> call, Response<FileListResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    parseFileList(response.body());
                } else {
                    runOnUiThread(() -> {
                        hideLoading();
                        Toast.makeText(FileBrowserActivity.this, "加载文件列表失败", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onFailure(Call<FileListResponse> call, Throwable t) {
                runOnUiThread(() -> {
                    hideLoading();
                    Toast.makeText(FileBrowserActivity.this, "网络错误: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void parseFileList(FileListResponse response) {
        List<FileItem> files = new ArrayList<>();

        try {
            List<FileItem> list = response.getList();
            if (list != null) {
                for (FileItem fileItem : list) {
                    // 只显示目录和音频文件
                    if (fileItem.getIsdir() == 1 || fileItem.isAudioFile()) {
                        files.add(fileItem);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentFiles = files;

        runOnUiThread(() -> {
            hideLoading();
            currentPathText.setText(currentPath);
            fileAdapter.setFiles(files);

            if (files.isEmpty()) {
                showEmpty();
            } else {
                hideEmpty();
            }
        });
    }

    private void navigateUp() {
        if ("/".equals(currentPath)) {
            finish();
            return;
        }

        // 获取父目录
        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash > 0) {
            currentPath = currentPath.substring(0, lastSlash);
        } else {
            currentPath = "/";
        }

        loadFiles();
    }

    private void toggleSelectAll() {
        boolean allSelected = fileAdapter.areAllAudioFilesSelected();
        fileAdapter.selectAllAudioFiles(!allSelected);
        updateSelectionInfo();
    }

    private void clearSelection() {
        fileAdapter.clearSelection();
        updateSelectionInfo();
    }

    private void addSelectedFiles() {
        List<FileItem> selectedFiles = fileAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 将选中的文件添加到播放列表
        for (FileItem fileItem : selectedFiles) {
            Song song = new Song();
            song.setPlaylistId(playlistId);
            song.setFsId(fileItem.getFsId());
            song.setTitle(fileItem.getServerFilename());
            song.setPath(fileItem.getPath());
            song.setSize(fileItem.getSize());
            song.setAddedTime(System.currentTimeMillis());

            playlistManager.addSongToPlaylist(playlistId, song, new PlaylistManager.OnResultListener() {
                @Override
                public void onSuccess(Object result) {
                    // 成功添加
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(FileBrowserActivity.this, "添加失败: " + error, Toast.LENGTH_SHORT).show());
                }
            });
        }

        Toast.makeText(this, "已添加 " + selectedFiles.size() + " 个文件", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void updateSelectionInfo() {
        int selectedCount = fileAdapter.getSelectedCount();
        if (selectedCount > 0) {
            selectionInfo.setVisibility(View.VISIBLE);
            selectedCountText.setText("已选择 " + selectedCount + " 个文件");
        } else {
            selectionInfo.setVisibility(View.GONE);
        }
    }

    private void showLoading() {
        loadingLayout.setVisibility(View.VISIBLE);
        fileRecyclerView.setVisibility(View.GONE);
        emptyLayout.setVisibility(View.GONE);
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
        fileRecyclerView.setVisibility(View.VISIBLE);
    }

    private void showEmpty() {
        emptyLayout.setVisibility(View.VISIBLE);
        fileRecyclerView.setVisibility(View.GONE);
    }

    private void hideEmpty() {
        emptyLayout.setVisibility(View.GONE);
        fileRecyclerView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onFileClick(FileItem file) {
        if (file.getIsdir() == 1) {
            // 进入目录
            currentPath = file.getPath();
            loadFiles();
        }
    }

    @Override
    public void onFileCheckChanged(FileItem file, boolean isChecked) {
        updateSelectionInfo();
    }
}