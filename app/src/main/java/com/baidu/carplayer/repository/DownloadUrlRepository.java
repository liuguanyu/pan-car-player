package com.baidu.carplayer.repository;

import androidx.annotation.Nullable;

/** 获取歌曲可播放地址的边界；Service 不直接依赖 Retrofit。 */
public interface DownloadUrlRepository {

    interface Callback {
        void onSuccess(String playableUrl);

        /** @param authExpired 为 true 时表示需要重新登录 */
        void onError(String message, boolean authExpired, @Nullable Throwable cause);
    }

    /** 返回可取消句柄；调用方切歌或销毁时应调用 cancel。 */
    Cancellable resolve(long fsId, Callback callback);

    /**
     * 缓存的可播放 URL；无缓存或已过期返回 null。
     * 默认无缓存，返回 null。
     */
    default String getCachedUrl(long fsId) {
        return null;
    }

    interface Cancellable {
        void cancel();
    }
}
