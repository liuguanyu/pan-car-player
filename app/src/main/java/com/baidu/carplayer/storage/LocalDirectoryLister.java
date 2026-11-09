package com.baidu.carplayer.storage;

import com.baidu.carplayer.model.FileItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 本地目录列举，锁定在浏览根目录之内。纯 java.io，可单测。 */
public final class LocalDirectoryLister {
    public static final String UNREADABLE = "无法读取该目录";

    private final File root;

    public LocalDirectoryLister(File root) {
        this.root = canonical(root);
    }

    public File getRoot() {
        return root;
    }

    /** 列出目录（未过滤、未排序）；不可读或越界抛 IOException。 */
    public List<FileItem> list(File dir) throws IOException {
        File d = canonical(dir);
        if (!isWithinRoot(d)) throw new IOException(UNREADABLE);
        File[] children = d.listFiles();
        if (children == null) throw new IOException(UNREADABLE);
        List<FileItem> items = new ArrayList<>(children.length);
        for (File f : children) {
            if (f.isHidden()) continue;
            items.add(toItem(f));
        }
        return items;
    }

    /** 上一级目录；已在根目录时返回 null。 */
    public File parentOf(File dir) {
        File d = canonical(dir);
        if (d.equals(root)) return null;
        File parent = d.getParentFile();
        return parent != null && isWithinRoot(parent) ? parent : null;
    }

    public boolean isWithinRoot(File f) {
        String path = canonical(f).getPath();
        String rootPath = root.getPath();
        return path.equals(rootPath) || path.startsWith(rootPath.endsWith("/") ? rootPath : rootPath + "/");
    }

    public static FileItem toItem(File f) {
        boolean dir = f.isDirectory();
        // 本地文件没有 fs_id；用路径哈希做稳定行 id，选择结果以 path 为准。
        return new FileItem(f.getName(), f.getAbsolutePath(), dir ? 0 : f.length(), dir,
                f.getAbsolutePath().hashCode() & 0x7fffffffL, null);
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }
}
