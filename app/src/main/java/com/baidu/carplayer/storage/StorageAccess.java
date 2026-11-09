package com.baidu.carplayer.storage;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;

/**
 * 本机存储访问策略。车机没有可用的 SAF（DocumentsUI 是空壳），
 * 因此用“所有文件访问”+ 应用内浏览器；未授权时退回到应用专属目录（无需权限）。
 */
public final class StorageAccess {
    private StorageAccess() {}

    public static boolean hasAllFilesAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** API 29 走运行时权限；API 30+ 走设置页。 */
    public static boolean usesRuntimePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R;
    }

    /** 可解析的“所有文件访问”设置页，车机上可能不存在则返回 null。 */
    @Nullable
    public static Intent settingsIntent(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        PackageManager pm = context.getPackageManager();
        Intent app = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        if (app.resolveActivity(pm) != null) return app;
        Intent all = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        if (all.resolveActivity(pm) != null) return all;
        return null;
    }

    public static String adbGrantCommand(Context context) {
        return adbGrantCommand(Process.myUid() / 100000, context.getPackageName());
    }

    static String adbGrantCommand(int userId, String packageName) {
        return "adb shell appops set --user " + userId + " " + packageName + " MANAGE_EXTERNAL_STORAGE allow";
    }

    /** 授权后的浏览根目录（共享存储根，如 /storage/emulated/10）。 */
    public static File sharedRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /** 无需权限的应用专属目录。 */
    public static File appRoot(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir != null && (dir.isDirectory() || dir.mkdirs())) return dir;
        return context.getFilesDir();
    }
}
