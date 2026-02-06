package com.baidu.carplayer.network;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 重试拦截器 - 处理车载环境网络不稳定的情况
 */
public class RetryInterceptor implements Interceptor {
    
    private final int maxRetries;
    private final long retryDelayMs;
    
    public RetryInterceptor(int maxRetries) {
        this.maxRetries = maxRetries;
        this.retryDelayMs = 1000;
    }
    
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        Response response = null;
        IOException exception = null;
        
        int tryCount = 0;
        while (tryCount <= maxRetries) {
            try {
                response = chain.proceed(request);
                if (response.isSuccessful()) {
                    return response;
                } else {
                    // 如果是5xx服务器错误，尝试重试
                    if (response.code() >= 500) {
                        response.close();
                    } else {
                        // 4xx客户端错误，不重试
                        return response;
                    }
                }
            } catch (IOException e) {
                exception = e;
            }
            
            tryCount++;
            if (tryCount <= maxRetries) {
                try {
                    Thread.sleep(retryDelayMs * tryCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry", e);
                }
            }
        }
        
        if (response != null) {
            return response;
        } else {
            throw exception != null ? exception : new IOException("Unknown error");
        }
    }
}