package com.baidu.carplayer.backup;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 备份文件名：默认 playlists-backup-YYYY-MM-DD.json（本地日期），自动补 .json，拒绝非法名。 */
public final class BackupFileNames {
    private BackupFileNames() {}

    public static String defaultName(Date date) {
        return "playlists-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + ".json";
    }

    /** 规范化用户输入；非法（空、含路径分隔符、仅为 . 或 ..）返回 null。 */
    @Nullable
    public static String normalize(@Nullable String input) {
        if (input == null) return null;
        String name = input.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20) return null;
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name = name + ".json";
        return name.length() > 255 ? null : name;
    }
}
