package com.baidu.carplayer.storage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** 原子写入：先写同目录临时文件并 fsync，再 rename 覆盖目标；失败清理临时文件。 */
public final class LocalBackupWriter {
    private LocalBackupWriter() {}

    public static File write(File dir, String fileName, byte[] content) throws IOException {
        if (dir == null || !dir.isDirectory()) throw new IOException("目录不存在");
        File target = new File(dir, fileName);
        File tmp = new File(dir, "." + fileName + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content);
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                throw new IOException("无法写入 " + target.getPath());
            }
            return target;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    public static String readText(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException("文件过大");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
