package com.baidu.carplayer.browser;

import com.baidu.carplayer.model.FileItem;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 浏览器列表的过滤与排序：文件夹优先，按名称（中文友好、稳定）排序。 */
public final class FileListing {
    private FileListing() {}

    public static List<FileItem> filterAndSort(List<FileItem> items, BrowserMode mode, boolean ascending) {
        List<FileItem> out = new ArrayList<>();
        for (FileItem item : items) {
            if (mode.accepts(item)) out.add(item);
        }
        out.sort(comparator(ascending));
        return out;
    }

    public static Comparator<FileItem> comparator(boolean ascending) {
        Collator collator = Collator.getInstance(Locale.CHINESE);
        collator.setStrength(Collator.PRIMARY); // 忽略大小写和音调差异
        return (f1, f2) -> {
            // 文件夹始终在前，不受正倒序影响
            if (f1.getIsdir() != f2.getIsdir()) {
                return f1.getIsdir() == 1 ? -1 : 1;
            }
            int result = collator.compare(f1.getServerFilename(), f2.getServerFilename());
            // Collator 可能判定相等，补充稳定的二级排序键
            if (result == 0) result = f1.getServerFilename().compareTo(f2.getServerFilename());
            if (result == 0) result = f1.getPath().compareTo(f2.getPath());
            if (result == 0) result = Long.compare(f1.getFsId(), f2.getFsId());
            return ascending ? result : -result;
        };
    }
}
