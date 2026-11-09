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
import com.baidu.carplayer.browser.BrowserMode;
import com.baidu.carplayer.browser.FileListing;
import com.baidu.carplayer.concurrent.AppExecutors;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.LogRedactor;
import com.baidu.carplayer.repository.CloudFileRepository;
import com.baidu.carplayer.repository.CloudMusicScanner;
import com.baidu.carplayer.repository.Repositories;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;


/**
 * 文件浏览器Activity - 从百度网盘选择音频文件
 */
public class FileBrowserActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener {

    public static final String EXTRA_PLAYLIST_ID = "playlist_id";
    /** 选择模式的返回值：选中的网盘路径（文件或目录）与文件 fs_id。 */
    public static final String RESULT_PATH = "result_path";
    public static final String RESULT_FS_ID = "result_fs_id";

    /** 选择网盘上的备份 JSON 或目录。 */
    public static Intent pickIntent(android.content.Context context, BrowserMode mode) {
        return new Intent(context, FileBrowserActivity.class).putExtra(BrowserMode.EXTRA, mode.name());
    }

    private RecyclerView fileRecyclerView;
    private FileAdapter fileAdapter;
    private TextView currentPathText;
    private TextView selectedCountText;
    private ImageButton backButton;
    private ImageButton sortButton;
    private MaterialButton selectAllButton;
    private MaterialButton addButton;
    private MaterialButton clearSelectionButton;
    private MaterialButton refreshButton;
    private View selectionInfo;
    private View loadingLayout;
    private View emptyLayout;

    private String playlistId;
    private BrowserMode mode = BrowserMode.ADD_SONGS;
    private String currentPath = "/";
    private List<FileItem> currentFiles = new ArrayList<>();
    private PlaylistManager playlistManager;
    private CloudFileRepository cloudFiles;
    // 目录加载代次：只显示最新一次请求的结果。
    private long loadGeneration;
    private boolean sortAscending = true; // 排序状态：true为正序，false为倒序
    
    // 用于递归扫描文件夹
    private boolean addInProgress = false;
    private android.app.ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        mode = BrowserMode.fromName(getIntent().getStringExtra(BrowserMode.EXTRA));
        playlistId = getIntent().getStringExtra(EXTRA_PLAYLIST_ID);
        if (!mode.isPicker() && playlistId == null) {
            Toast.makeText(this, "播放列表ID为空", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        initData();
        loadFiles();
        
        // 提示用户选择
        Toast.makeText(this, mode == BrowserMode.PICK_JSON ? "请选择播放列表备份文件（.json）"
                : mode == BrowserMode.PICK_DIR ? "请进入目标目录后点击“选择此目录”"
                : "请选择文件或文件夹添加到播放列表", Toast.LENGTH_LONG).show();
    }

    private void initViews() {
        fileRecyclerView = findViewById(R.id.file_recycler_view);
        currentPathText = findViewById(R.id.current_path);
        selectedCountText = findViewById(R.id.selected_count);
        backButton = findViewById(R.id.back_button);
        sortButton = findViewById(R.id.sort_button);
        selectAllButton = findViewById(R.id.select_all_button);
        addButton = findViewById(R.id.add_button);
        clearSelectionButton = findViewById(R.id.clear_selection_button);
        refreshButton = findViewById(R.id.refresh_button);
        selectionInfo = findViewById(R.id.selection_info);
        loadingLayout = findViewById(R.id.loading_layout);
        emptyLayout = findViewById(R.id.empty_layout);

        // 设置RecyclerView为列表布局
        fileRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        fileAdapter = new FileAdapter();
        fileAdapter.setOnFileClickListener(this);
        fileRecyclerView.setAdapter(fileAdapter);

        // 设置按钮点击事件
        backButton.setOnClickListener(v -> navigateUp());
        sortButton.setOnClickListener(v -> toggleSortOrder());
        selectAllButton.setOnClickListener(v -> toggleSelectAll());
        addButton.setOnClickListener(v -> {
            if (mode == BrowserMode.PICK_DIR) returnResult(currentPath, 0);
            else addSelectedFiles();
        });
        if (mode.isPicker()) {
            fileAdapter.setPickMode(true);
            selectAllButton.setVisibility(View.GONE);
            if (mode == BrowserMode.PICK_DIR) addButton.setText("选择此目录");
            else addButton.setVisibility(View.GONE);
        }
        clearSelectionButton.setOnClickListener(v -> clearSelection());
        refreshButton.setOnClickListener(v -> loadFiles());
    }

    private void initData() {
        if (!mode.isPicker()) playlistManager = new PlaylistManager(this);
        cloudFiles = Repositories.cloudFiles(this);
        if (!cloudFiles.hasToken()) {
            Toast.makeText(this, "未登录，请先登录", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void loadFiles() {
        if (!cloudFiles.hasToken()) {
            return;
        }

        showLoading();
        final String path = currentPath;
        final long generation = ++loadGeneration;
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                List<FileItem> items = cloudFiles.listFolder(path);
                showFileList(generation, items);
            } catch (CloudMusicScanner.ScanException e) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    hideLoading();
                    Toast.makeText(FileBrowserActivity.this,
                            "加载文件列表失败: " + LogRedactor.redact(e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** 在后台线程排序；只有最新一次目录加载的结果会被显示，避免快速切目录时旧结果覆盖。 */
    private void showFileList(long generation, List<FileItem> list) {
        // 在 setFiles 之前先过滤排序（文件夹优先 → 名称），避免 UI 先显示未排序列表再闪烁
        List<FileItem> files = FileListing.filterAndSort(list, mode, sortAscending);

        runOnUiThread(() -> {
            if (generation != loadGeneration || isFinishing()) return;
            currentFiles = files;
            hideLoading();
            currentPathText.setText(currentPath);
            // 数据已排序完毕，适配器直接展示，不再二次排序
            fileAdapter.setFiles(files);
            // 切换目录时复用同一个 RecyclerView，必须显式回到列表顶部，
            // 否则 LayoutManager 会保留上一级目录的滚动位置。
            fileRecyclerView.post(() -> fileRecyclerView.scrollToPosition(0));

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

    /**
     * 切换排序顺序
     */
    private void toggleSortOrder() {
        sortAscending = !sortAscending;
        // 更新排序按钮图标
        if (sortAscending) {
            sortButton.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        } else {
            sortButton.setImageResource(android.R.drawable.ic_menu_sort_alphabetically);
        }
        // 对当前文件列表进行排序
        fileAdapter.sortByName(sortAscending);
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
        if (addInProgress) {
            return;
        }
        addInProgress = true;

        // 显示进度对话框
        progressDialog = new android.app.ProgressDialog(this);
        progressDialog.setMessage("正在扫描网盘文件...");
        progressDialog.setCancelable(false);
        progressDialog.show();

        final List<FileItem> selection = new ArrayList<>(selectedFiles);
        // 扫描完整成功后才写库；任一目录失败都不写入，避免出现残缺列表。
        AppExecutors.getInstance().networkIO().execute(() -> {
            List<FileItem> audioFiles;
            try {
                audioFiles = cloudFiles.scanSelection(selection);
            } catch (CloudMusicScanner.ScanException e) {
                onAddFinished("添加失败: " + LogRedactor.redact(e.getMessage()), false);
                return;
            }
            if (audioFiles.isEmpty()) {
                onAddFinished("未找到可添加的音频文件", false);
                return;
            }
            playlistManager.addSongsToPlaylist(playlistId, toSortedSongs(audioFiles),
                    new PlaylistManager.OnResultListener() {
                        @Override
                        public void onSuccess(Object result) {
                            int added = (Integer) result;
                            int skipped = audioFiles.size() - added;
                            String message = "已添加 " + added + " 首歌曲"
                                    + (skipped > 0 ? "，" + skipped + " 首已在列表中" : "");
                            onAddFinished(message, true);
                        }

                        @Override
                        public void onError(String error) {
                            onAddFinished("添加失败: " + error, false);
                        }
                    });
        });
    }

    /** 按名称排序，保证添加顺序稳定。 */
    private List<Song> toSortedSongs(List<FileItem> files) {
        List<Song> songs = new ArrayList<>(files.size());
        for (FileItem item : files) {
            Song song = new Song(item.getFsId(), playlistId, item.getServerFilename(), item.getPath(),
                    item.getSize(), 0L);
            songs.add(song);
        }
        java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINESE);
        collator.setStrength(java.text.Collator.PRIMARY);
        songs.sort((s1, s2) -> {
            int result = collator.compare(s1.getTitle(), s2.getTitle());
            if (result == 0) {
                result = s1.getTitle().compareTo(s2.getTitle());
            }
            if (result == 0) {
                result = s1.getPath().compareTo(s2.getPath());
            }
            return result;
        });
        return songs;
    }

    private void onAddFinished(String message, boolean success) {
        runOnUiThread(() -> {
            addInProgress = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            Toast.makeText(this, message, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            if (success) {
                finish();
            }
        });
    }

    private void updateSelectionInfo() {
        int selectedFileCount = fileAdapter.getSelectedFileCount();
        int selectedFolderCount = fileAdapter.getSelectedFolderCount();
        int totalSelected = selectedFileCount + selectedFolderCount;
        
        if (totalSelected > 0) {
            selectionInfo.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder("已选择 ");
            if (selectedFileCount > 0) {
                sb.append(selectedFileCount).append(" 个文件");
            }
            if (selectedFolderCount > 0) {
                if (selectedFileCount > 0) {
                    sb.append(", ");
                }
                sb.append(selectedFolderCount).append(" 个文件夹");
            }
            selectedCountText.setText(sb.toString());
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
        } else if (mode == BrowserMode.PICK_JSON) {
            returnResult(file.getPath(), file.getFsId());
        }
    }

    private void returnResult(String path, long fsId) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_PATH, path).putExtra(RESULT_FS_ID, fsId));
        finish();
    }

    @Override
    public void onFileCheckChanged(FileItem file, boolean isChecked) {
        updateSelectionInfo();
    }
}