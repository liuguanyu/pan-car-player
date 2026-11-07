package com.baidu.carplayer.adapter.rows;

import com.baidu.carplayer.model.FileItem;

import java.util.Locale;

/** 网盘文件行的不可变渲染模型及点击决策；id 为稳定的 fsId。 */
public final class FileRow {
    public enum Kind { FOLDER, AUDIO, OTHER }
    public enum Action { OPEN, TOGGLE, NONE }

    public final long id;
    public final String name;
    public final Kind kind;
    /** 文件夹为 null（不显示大小）。 */
    public final String sizeText;
    public final boolean selected;

    public FileRow(long id, String name, Kind kind, String sizeText, boolean selected) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.sizeText = sizeText;
        this.selected = selected;
    }

    public static FileRow of(FileItem file, boolean selected) {
        Kind kind = file.getIsdir() == 1 ? Kind.FOLDER : file.isAudioFile() ? Kind.AUDIO : Kind.OTHER;
        return new FileRow(file.getFsId(), file.getServerFilename(), kind,
                kind == Kind.FOLDER ? null : formatSize(file.getSize()), selected);
    }

    /** 点击：文件夹进入，文件切换选中。 */
    public Action clickAction() {
        return kind == Kind.FOLDER ? Action.OPEN : Action.TOGGLE;
    }

    /** 长按：仅文件夹切换选中（选中整个目录）。 */
    public Action longClickAction() {
        return kind == Kind.FOLDER ? Action.TOGGLE : Action.NONE;
    }

    public static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        } else if (size < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}
