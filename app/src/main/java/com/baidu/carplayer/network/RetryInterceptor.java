package com.baidu.carplayer.network;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 重试拦截器 - 处理车载环境网络不稳定的情况。
 *
 * <p>已取消的调用（例如快速切歌时被替换的下载地址请求）绝不重试：
 * 否则它们会在退避等待中占住 OkHttp 每个 host 的并发名额，拖慢最新请求。
 */
public class RetryInterceptor implements Interceptor {
    /** 退避等待的检查粒度，保证取消后能尽快释放线程。 */
    private static final long SLEEP_SLICE_MS = 100;

    private final int maxRetries;
    private final long retryDelayMs;

    public RetryInterceptor(int maxRetries) {
        this(maxRetries, 1000);
    }

    RetryInterceptor(int maxRetries, long retryDelayMs) {
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException exception = null;

        int tryCount = 0;
        while (tryCount <= maxRetries) {
            throwIfCanceled(chain);
            try {
                response = chain.proceed(request);
                if (response.isSuccessful() || response.code() < 500) {
                    // 成功或 4xx 客户端错误：不重试
                    return response;
                }
                if (tryCount == maxRetries) {
                    return response; // 最后一次的 5xx 原样返回
                }
                response.close();
                response = null;
            } catch (IOException e) {
                if (chain.call().isCanceled()) {
                    throw e;
                }
                exception = e;
            }

            tryCount++;
            if (tryCount <= maxRetries) {
                backoff(chain, retryDelayMs * tryCount);
            }
        }

        throw exception != null ? exception : new IOException("Unknown error");
    }

    private static void throwIfCanceled(Chain chain) throws IOException {
        if (chain.call().isCanceled()) {
            throw new IOException("Canceled");
        }
    }

    private static void backoff(Chain chain, long delayMs) throws IOException {
        long remaining = delayMs;
        while (remaining > 0) {
            throwIfCanceled(chain);
            long slice = Math.min(SLEEP_SLICE_MS, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during retry", e);
            }
            remaining -= slice;
        }
        throwIfCanceled(chain);
    }
}
