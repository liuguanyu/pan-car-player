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
     * 预取用途解析：与 {@link #resolve} 共享缓存与在途请求，但实现可以放宽重试策略，
     * 保证后台预取失败时不会用退避重试长期占用前台播放要用的连接。
     * 默认与 {@link #resolve} 完全一致。
     */
    default Cancellable resolvePrefetch(long fsId, Callback callback) {
        return resolve(fsId, callback);
    }

    /**
     * 缓存的可播放 URL；无缓存或已过期返回 null。
     * 默认无缓存，返回 null。
     */
    default String getCachedUrl(long fsId) {
        return null;
    }

    /** 作废指定歌曲的缓存链接（下载返回 401/403 时用于强制重取）。默认无操作。 */
    default void evict(long fsId) {
    }

    interface Cancellable {
        void cancel();
    }
}
