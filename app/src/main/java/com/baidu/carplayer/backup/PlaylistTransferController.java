package com.baidu.carplayer.backup;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.baidu.carplayer.FileBrowserActivity;
import com.baidu.carplayer.LocalFileBrowserActivity;
import com.baidu.carplayer.browser.BrowserMode;
import com.baidu.carplayer.concurrent.AppExecutors;
import com.baidu.carplayer.manager.PlaylistManager;
import com.baidu.carplayer.model.Playlist;
import com.baidu.carplayer.model.Song;
import com.baidu.carplayer.network.LogRedactor;
import com.baidu.carplayer.repository.CloudBackupRepository;
import com.baidu.carplayer.repository.Repositories;
import com.baidu.carplayer.storage.LocalBackupWriter;
import com.baidu.carplayer.storage.StorageAccess;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 列表页的导入/导出流程编排（对话框序列 + 页面结果回调 + 后台任务）。
 * 必须在宿主 Activity 的 onCreate 中构造（注册 ActivityResult 回调）。
 */
public final class PlaylistTransferController {
    private enum Pending { NONE, EXPORT_LOCAL, EXPORT_CLOUD, IMPORT_LOCAL, IMPORT_CLOUD }

    private final AppCompatActivity activity;
    private final PlaylistManager playlistManager;
    private final Runnable onImported;
    private final CloudBackupRepository cloud;

    private final ActivityResultLauncher<Intent> localBrowser;
    private final ActivityResultLauncher<Intent> cloudBrowser;
    private final ActivityResultLauncher<Intent> settingsPage;
    private final ActivityResultLauncher<String> legacyPermission;

    // 跨页面回调的待办状态
    private Pending pending = Pending.NONE;
    private List<Playlist> pendingExport = new ArrayList<>();
    private Consumer<File> pendingStorage;

    public PlaylistTransferController(AppCompatActivity activity, PlaylistManager playlistManager, Runnable onImported) {
        this.activity = activity;
        this.playlistManager = playlistManager;
        this.onImported = onImported;
        this.cloud = Repositories.cloudBackups(activity);
        localBrowser = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onLocalPicked);
        cloudBrowser = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::onCloudPicked);
        settingsPage = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> onPermissionReturned());
        legacyPermission = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> onPermissionReturned());
    }

    // ---------------------------------------------------------------- 入口

    public void showMenu() {
        new AlertDialog.Builder(activity)
                .setItems(new String[]{"导入列表", "导出列表"}, (d, which) -> {
                    if (which == 0) startImport(); else startExport();
                })
                .show();
    }

    public void startImport() {
        new AlertDialog.Builder(activity)
                .setTitle("导入列表")
                .setItems(new String[]{"从本机文件导入", "从百度网盘导入"}, (d, which) -> {
                    if (which == 0) {
                        withStorage(root -> {
                            pending = Pending.IMPORT_LOCAL;
                            localBrowser.launch(LocalFileBrowserActivity.intent(activity, BrowserMode.PICK_JSON, root));
                        });
                    } else if (requireLogin()) {
                        pending = Pending.IMPORT_CLOUD;
                        cloudBrowser.launch(FileBrowserActivity.pickIntent(activity, BrowserMode.PICK_JSON));
                    }
                })
                .show();
    }

    public void startExport() {
        playlistManager.getAllPlaylists(new PlaylistManager.OnPlaylistsLoadListener() {
            @Override public void onSuccess(List<Playlist> playlists) {
                ui(() -> chooseExportPlaylists(playlists));
            }
            @Override public void onError(String error) {
                toast("加载播放列表失败: " + error);
            }
        });
    }

    // ---------------------------------------------------------------- 导出

    private void chooseExportPlaylists(List<Playlist> playlists) {
        if (playlists.isEmpty()) {
            toast("没有可导出的播放列表");
            return;
        }
        String[] labels = new String[playlists.size()];
        boolean[] checked = new boolean[playlists.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = playlists.get(i).getName() + "（" + playlists.get(i).getSongCount() + " 首）";
            checked[i] = true;
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择要导出的列表")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("下一步", (d, w) -> {
                    List<Playlist> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(playlists.get(i));
                    if (selected.isEmpty()) {
                        toast("请至少选择一个列表");
                        return;
                    }
                    pendingExport = selected;
                    chooseExportTarget();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseExportTarget() {
        new AlertDialog.Builder(activity)
                .setTitle("导出到")
                .setItems(new String[]{"本机文件", "百度网盘"}, (d, which) -> {
                    if (which == 0) {
                        withStorage(root -> {
                            pending = Pending.EXPORT_LOCAL;
                            localBrowser.launch(LocalFileBrowserActivity.intent(activity, BrowserMode.PICK_DIR, root));
                        });
                    } else if (requireLogin()) {
                        pending = Pending.EXPORT_CLOUD;
                        cloudBrowser.launch(FileBrowserActivity.pickIntent(activity, BrowserMode.PICK_DIR));
                    }
                })
                .show();
    }

    private void askFileName(Consumer<String> onName) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(BackupFileNames.defaultName(new Date()));
        input.setSelectAllOnFocus(false);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle("文件名")
                .setView(box)
                .setPositiveButton("导出", (d, w) -> {
                    String name = BackupFileNames.normalize(input.getText().toString());
                    if (name == null) {
                        toast("文件名无效");
                        return;
                    }
                    onName.accept(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportToLocal(File dir) {
        askFileName(name -> {
            File target = new File(dir, name);
            Runnable write = () -> runExport("正在导出...", content -> {
                File written = LocalBackupWriter.write(dir, name, content);
                // 让媒体库/USB(MTP) 能看到新文件
                MediaScannerConnection.scanFile(activity.getApplicationContext(),
                        new String[]{written.getAbsolutePath()}, new String[]{"application/json"}, null);
                return "已导出到 " + written.getAbsolutePath();
            });
            if (target.exists()) {
                new AlertDialog.Builder(activity)
                        .setTitle("文件已存在，是否覆盖？")
                        .setMessage(target.getAbsolutePath())
                        .setPositiveButton("覆盖", (d, w) -> write.run())
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                write.run();
            }
        });
    }

    private void exportToCloud(String dir) {
        askFileName(name -> {
            String remote = CloudBackupRepository.join(dir, name);
            runExport("正在上传到网盘...", content -> {
                try {
                    cloud.upload(remote, content);
                } catch (CloudBackupRepository.CloudException e) {
                    throw new Exception("上传失败: " + e.getMessage());
                }
                return "已上传到网盘 " + remote;
            });
        });
    }

    private interface ExportSink {
        String write(byte[] content) throws Exception;
    }

    private void runExport(String progressText, ExportSink sink) {
        List<Playlist> selected = new ArrayList<>(pendingExport);
        ProgressDialog progress = progress(progressText);
        AppExecutors.getInstance().networkIO().execute(() -> {
            String message;
            try {
                Map<String, List<Song>> songs = playlistManager.songsForPlaylistsNow(selected);
                byte[] content = PlaylistBackupCodec.write(selected, songs, System.currentTimeMillis())
                        .getBytes(StandardCharsets.UTF_8);
                message = sink.write(content);
            } catch (Exception e) {
                message = e.getMessage() != null && e.getMessage().startsWith("上传失败")
                        ? e.getMessage() : "导出失败: " + e.getMessage();
            }
            final String text = LogRedactor.redact(message);
            ui(() -> {
                dismiss(progress);
                longToast(text);
            });
        });
    }

    // ---------------------------------------------------------------- 导入

    private interface TextSource {
        String read() throws Exception;
    }

    private void importFrom(TextSource source) {
        ProgressDialog progress = progress("正在读取备份文件...");
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                PlaylistBackupCodec.ParsedBackup backup = PlaylistBackupCodec.parse(source.read());
                Set<String> taken = playlistManager.playlistNamesNow();
                ui(() -> {
                    dismiss(progress);
                    showPreview(backup, taken);
                });
            } catch (Exception e) {
                String msg = LogRedactor.redact(e.getMessage());
                ui(() -> {
                    dismiss(progress);
                    longToast("导入失败: " + msg);
                });
            }
        });
    }

    private void showPreview(PlaylistBackupCodec.ParsedBackup backup, Set<String> existing) {
        List<BackupPlaylist> lists = backup.getPlaylists();
        String[] labels = new String[lists.size()];
        boolean[] checked = new boolean[lists.size()];
        Set<String> taken = new java.util.HashSet<>(existing);
        for (int i = 0; i < labels.length; i++) {
            BackupPlaylist p = lists.get(i);
            String finalName = ImportNaming.uniqueName(p.getName(), taken);
            taken.add(finalName);
            labels[i] = (finalName.equals(p.getName()) ? finalName : p.getName() + " → " + finalName)
                    + "（" + p.getSongs().size() + " 首）";
            checked[i] = true;
        }
        String title = "导入预览：" + lists.size() + " 个列表，" + backup.totalSongs() + " 首"
                + (backup.totalSkipped() > 0 ? "，跳过 " + backup.totalSkipped() + " 个无效条目" : "");
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("导入", (d, w) -> {
                    List<BackupPlaylist> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) selected.add(lists.get(i));
                    if (selected.isEmpty()) {
                        toast("请至少选择一个列表");
                        return;
                    }
                    runImport(selected);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runImport(List<BackupPlaylist> selected) {
        ProgressDialog progress = progress("正在导入...");
        playlistManager.importPlaylists(selected, new PlaylistManager.OnResultListener() {
            @Override public void onSuccess(Object result) {
                int songs = 0;
                List<?> imported = (List<?>) result;
                for (Object o : imported) songs += ((PlaylistManager.ImportedPlaylist) o).songCount;
                final String text = "已导入 " + imported.size() + " 个列表，共 " + songs + " 首歌曲";
                ui(() -> {
                    dismiss(progress);
                    longToast(text);
                    onImported.run();
                });
            }
            @Override public void onError(String error) {
                ui(() -> {
                    dismiss(progress);
                    longToast("导入失败: " + error);
                });
            }
        });
    }

    // ---------------------------------------------------------------- 页面结果

    private void onLocalPicked(ActivityResult result) {
        Pending what = pending;
        pending = Pending.NONE;
        if (result.getResultCode() != AppCompatActivity.RESULT_OK || result.getData() == null) return;
        String path = result.getData().getStringExtra(LocalFileBrowserActivity.RESULT_PATH);
        if (path == null) return;
        File file = new File(path);
        if (what == Pending.EXPORT_LOCAL) {
            exportToLocal(file);
        } else if (what == Pending.IMPORT_LOCAL) {
            importFrom(() -> LocalBackupWriter.readText(file, CloudBackupRepository.MAX_DOWNLOAD_BYTES));
        }
    }

    private void onCloudPicked(ActivityResult result) {
        Pending what = pending;
        pending = Pending.NONE;
        if (result.getResultCode() != AppCompatActivity.RESULT_OK || result.getData() == null) return;
        Intent data = result.getData();
        String path = data.getStringExtra(FileBrowserActivity.RESULT_PATH);
        if (what == Pending.EXPORT_CLOUD && path != null) {
            exportToCloud(path);
        } else if (what == Pending.IMPORT_CLOUD) {
            long fsId = data.getLongExtra(FileBrowserActivity.RESULT_FS_ID, 0);
            if (fsId > 0) importFrom(() -> cloud.downloadText(fsId));
        }
    }

    // ---------------------------------------------------------------- 存储权限

    /** 确保可浏览本机存储；回调参数为浏览根目录。 */
    private void withStorage(Consumer<File> onRoot) {
        if (StorageAccess.hasAllFilesAccess(activity)) {
            onRoot.accept(StorageAccess.sharedRoot());
            return;
        }
        File appRoot = StorageAccess.appRoot(activity);
        if (StorageAccess.usesRuntimePermission()) {
            pendingStorage = onRoot;
            legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        Intent settings = StorageAccess.settingsIntent(activity);
        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("需要“所有文件访问”权限")
                .setNeutralButton("仅使用应用目录", (d, w) -> onRoot.accept(appRoot))
                .setNegativeButton("取消", null);
        if (settings != null) {
            b.setMessage("浏览本机存储中的备份文件需要“所有文件访问”权限。\n\n未授权时只能访问应用目录：\n"
                    + appRoot.getAbsolutePath())
             .setPositiveButton("去授权", (d, w) -> {
                 pendingStorage = onRoot;
                 settingsPage.launch(settings);
             });
        } else {
            b.setMessage("本机系统没有授权入口，可通过电脑执行：\n\n" + StorageAccess.adbGrantCommand(activity)
                    + "\n\n未授权时只能访问应用目录：\n" + appRoot.getAbsolutePath());
        }
        b.show();
    }

    private void onPermissionReturned() {
        Consumer<File> next = pendingStorage;
        pendingStorage = null;
        if (next == null) return;
        if (StorageAccess.hasAllFilesAccess(activity)) {
            next.accept(StorageAccess.sharedRoot());
        } else {
            toast("未获得所有文件访问权限");
        }
    }

    // ---------------------------------------------------------------- 工具

    private boolean requireLogin() {
        if (cloud.hasToken()) return true;
        toast("未登录，请先登录");
        return false;
    }

    private ProgressDialog progress(String text) {
        ProgressDialog d = new ProgressDialog(activity);
        d.setMessage(text);
        d.setCancelable(false);
        d.show();
        return d;
    }

    private void dismiss(ProgressDialog d) {
        if (d.isShowing() && !activity.isDestroyed()) d.dismiss();
    }

    private void ui(Runnable r) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) r.run();
        });
    }

    private void toast(String text) {
        ui(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
    }

    private void longToast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }
}
