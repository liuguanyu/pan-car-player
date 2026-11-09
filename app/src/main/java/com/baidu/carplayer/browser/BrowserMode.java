package com.baidu.carplayer.browser;

import com.baidu.carplayer.model.FileItem;

import java.util.Locale;

/** 文件浏览器的用途：添加歌曲（原有行为）、选择备份 JSON、选择目录。 */
public enum BrowserMode {
    ADD_SONGS, PICK_JSON, PICK_DIR;

    public static final String EXTRA = "browser_mode";

    /** 当前模式下是否列出该条目。 */
    public boolean accepts(FileItem item) {
        if (item.getIsdir() == 1) return true;
        switch (this) {
            case ADD_SONGS: return item.isAudioFile();
            case PICK_JSON: return isJson(item.getServerFilename());
            default: return false;
        }
    }

    /** 选择模式下点击文件即返回，不做多选。 */
    public boolean isPicker() {
        return this != ADD_SONGS;
    }

    public static boolean isJson(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    public static BrowserMode fromName(String name) {
        if (name == null) return ADD_SONGS;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return ADD_SONGS;
        }
    }
}
