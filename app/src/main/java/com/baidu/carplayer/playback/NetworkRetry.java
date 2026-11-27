package com.baidu.carplayer.playback;

import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 网络错误的重试策略：区分"抖动可恢复"与"确定失败"，给出退避间隔。
 *
 * <p>纯逻辑，不接触播放器，便于在 JVM 上单测。真正的重试调度由
 * {@code AudioPlayerService} 用 Handler 完成。
 */
public final class NetworkRetry {
    /** 同一首歌最多自动重试次数；超过后把错误交给页面展示。 */
    public static final int MAX_ATTEMPTS = 4;
    /** 退避间隔：约 1.5s / 4s / 9s / 15s，避免弱网下形成请求风暴。 */
    private static final long[] DELAYS_MS = {1500L, 4000L, 9000L, 15000L};

    private NetworkRetry() {
    }

    /** 第 attempt 次重试（从 1 开始）前应等待的毫秒数。 */
    public static long delayFor(int attempt) {
        if (attempt <= 0) return DELAYS_MS[0];
        int index = Math.min(attempt, DELAYS_MS.length) - 1;
        return DELAYS_MS[index];
    }

    /**
     * 是否值得重试：超时、连接重置、DNS 失败等瞬时网络问题可重试；
     * 4xx（除 408/429）属于确定失败，重试没有意义。
     */
    public static boolean isTransient(Throwable error) {
        if (error == null) return false;
        Throwable cause = error;
        boolean transientIo = false;
        while (cause != null) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return code >= 500 || code == 408 || code == 429;
            }
            if (cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof SocketException) {
                transientIo = true;
            } else if (cause instanceof IOException) {
                transientIo = true;
            }
            cause = cause.getCause();
        }
        return transientIo;
    }
}
