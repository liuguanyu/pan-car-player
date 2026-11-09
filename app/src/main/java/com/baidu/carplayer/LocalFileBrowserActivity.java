package com.baidu.carplayer;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.baidu.carplayer.adapter.FileAdapter;
import com.baidu.carplayer.browser.BrowserMode;
import com.baidu.carplayer.browser.FileListing;
import com.baidu.carplayer.concurrent.AppExecutors;
import com.baidu.carplayer.model.FileItem;
import com.baidu.carplayer.storage.LocalDirectoryLister;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 本机文件浏览器：选择备份 JSON（PICK_JSON）或目标目录（PICK_DIR）。
 * 锁定在调用方给定的根目录内（授权时为共享存储根，否则为应用专属目录）。
 */
public class LocalFileBrowserActivity extends AppCompatActivity implements FileAdapter.OnFileClickListener {
    public static final String EXTRA_ROOT = "root";
    /** 返回值：选中文件或目录的绝对路径。 */
    public static final String RESULT_PATH = "result_path";

    public static Intent intent(Context context, BrowserMode mode, File root) {
        return new Intent(context, LocalFileBrowserActivity.class)
                .putExtra(BrowserMode.EXTRA, mode.name())
                .putExtra(EXTRA_ROOT, root.getAbsolutePath());
    }

    private BrowserMode mode;
    private LocalDirectoryLister lister;
    private File current;
    private long loadGeneration;
    private boolean sortAscending = true;

    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private TextView pathText;
    private View loadingLayout;
    private View emptyLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);
        mode = BrowserMode.fromName(getIntent().getStringExtra(BrowserMode.EXTRA));
        String root = getIntent().getStringExtra(EXTRA_ROOT);
        if (!mode.isPicker() || root == null) {
            finish();
            return;
        }
        lister = new LocalDirectoryLister(new File(root));
        current = lister.getRoot();

        recyclerView = findViewById(R.id.file_recycler_view);
        pathText = findViewById(R.id.current_path);
        loadingLayout = findViewById(R.id.loading_layout);
        emptyLayout = findViewById(R.id.empty_layout);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter();
        adapter.setPickMode(true);
        adapter.setOnFileClickListener(this);
        recyclerView.setAdapter(adapter);

        ImageButton back = findViewById(R.id.back_button);
        back.setOnClickListener(v -> navigateUp());
        ImageButton sort = findViewById(R.id.sort_button);
        sort.setOnClickListener(v -> {
            sortAscending = !sortAscending;
            sort.setImageResource(sortAscending ? android.R.drawable.ic_menu_sort_by_size
                    : android.R.drawable.ic_menu_sort_alphabetically);
            adapter.sortByName(sortAscending);
        });
        findViewById(R.id.select_all_button).setVisibility(View.GONE);
        findViewById(R.id.selection_info).setVisibility(View.GONE);
        MaterialButton pick = findViewById(R.id.add_button);
        if (mode == BrowserMode.PICK_DIR) {
            pick.setText("选择此目录");
            pick.setOnClickListener(v -> returnResult(current));
        } else {
            pick.setVisibility(View.GONE);
        }
        MaterialButton refresh = findViewById(R.id.refresh_button);
        refresh.setOnClickListener(v -> load(current));

        Toast.makeText(this, mode == BrowserMode.PICK_JSON ? "请选择播放列表备份文件（.json）"
                : "请进入目标目录后点击“选择此目录”", Toast.LENGTH_LONG).show();
        load(current);
    }

    @Override
    public void onBackPressed() {
        navigateUp();
    }

    private void navigateUp() {
        File parent = lister.parentOf(current);
        if (parent == null) {
            finish();   // 根目录返回：不带结果关闭
            return;
        }
        load(parent);
    }

    private void load(File dir) {
        final long generation = ++loadGeneration;
        loadingLayout.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyLayout.setVisibility(View.GONE);
        AppExecutors.getInstance().diskIO().execute(() -> {
            List<FileItem> items;
            try {
                items = FileListing.filterAndSort(lister.list(dir), mode, sortAscending);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing()) return;
                    loadingLayout.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    Toast.makeText(this, LocalDirectoryLister.UNREADABLE, Toast.LENGTH_SHORT).show();
                });
                return;   // 留在上一个目录
            }
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing()) return;
                current = dir;
                pathText.setText(dir.getAbsolutePath());
                loadingLayout.setVisibility(View.GONE);
                adapter.setFiles(items);
                recyclerView.post(() -> recyclerView.scrollToPosition(0));
                boolean empty = items.isEmpty();
                emptyLayout.setVisibility(empty ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    private void returnResult(File file) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_PATH, file.getAbsolutePath()));
        finish();
    }

    @Override
    public void onFileClick(FileItem file) {
        File f = new File(file.getPath());
        if (file.getIsdir() == 1) {
            load(f);
        } else if (mode == BrowserMode.PICK_JSON) {
            returnResult(f);
        }
    }

    @Override
    public void onFileCheckChanged(FileItem file, boolean isChecked) {
    }
}
