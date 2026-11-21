package com.baidu.carplayer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃落盘：真机没有 adb 时，未捕获异常的完整堆栈写入 App 私有目录，
 * 用户/开发者可直接查看或导出。
 *
 * <p>文件位置：{@code Android/data/com.baidu.carplayer/files/crash/}
 * （应用可见，车机文件管理器或 adb pull 均可取到），最多保留 10 份。
 */
public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    private static final int MAX_KEEP = 10;

    private CrashReporter() {}

    public static void install(final Context app) {
        Thread defaultMain = Thread.currentThread();
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable t) {
                String path = write(app, thread, t);
                Log.e(TAG, "uncaught exception on " + thread.getName() + " -> " + path, t);
                // 交给系统默认处理器，保持"崩溃即退出"的语义，只多做落盘
                if (original != null) original.uncaughtException(thread, t);
            }
        });
    }

    /** 写一份崩溃日志，返回文件路径（写失败返回 null，绝不二次抛异常）。 */
    static String write(Context app, Thread thread, Throwable t) {
        try {
            File dir = new File(app.getExternalFilesDir(null), "crash");
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = "crash_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date()) + ".log";
            File file = new File(dir, name);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("com.baidu.carplayer crash log");
            pw.println("thread: " + thread.getName() + (thread == Thread.currentThread() ? " (current)" : ""));
            pw.println("time: " + new Date());
            pw.println("android: " + android.os.Build.VERSION.RELEASE + " sdk " + android.os.Build.VERSION.SDK_INT);
            pw.println("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println("app version: " + versionName(app));
            pw.println();
            t.printStackTrace(pw);
            pw.close();
            FileOutputStream w = new FileOutputStream(file);
            w.write(sw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            w.close();
            prune(dir);
            return file.getAbsolutePath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String versionName(Context app) {
        try {
            return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("crash_"));
        if (files == null || files.length <= MAX_KEEP) return;
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = MAX_KEEP; i < files.length; i++) files[i].delete();
    }
}
