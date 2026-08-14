package com.panmusic.carplayer.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 自动重试拦截器 — 仅在 IO 异常（网络断开等）时重试
 *
 * HTTP 层面的 401/403 等由 Repository 层处理（AuthRepository.handleApiAuthError()）
 */
class RetryInterceptor(
    private val maxRetries: Int = 3
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var lastException: IOException? = null

        for (i in 0..maxRetries) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                lastException = e
            }
        }

        throw lastException ?: IOException("Max retries exceeded")
    }
}
