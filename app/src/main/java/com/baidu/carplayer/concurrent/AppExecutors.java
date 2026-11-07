package com.baidu.carplayer.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用级共享线程池，替代各处的裸 new Thread。
 * diskIO 为单线程：数据库写入天然串行，避免并发更新歌曲数量时互相覆盖。
 * networkIO 用于耗时的网盘扫描，避免阻塞数据库队列。
 */
public final class AppExecutors {
    private static volatile AppExecutors instance;

    private final ExecutorService diskIO;
    private final ExecutorService networkIO;

    private AppExecutors() {
        diskIO = Executors.newSingleThreadExecutor(named("app-disk-io"));
        networkIO = Executors.newFixedThreadPool(2, named("app-network-io"));
    }

    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) {
                    instance = new AppExecutors();
                }
            }
        }
        return instance;
    }

    public ExecutorService diskIO() {
        return diskIO;
    }

    public ExecutorService networkIO() {
        return networkIO;
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
