package com.baidu.carplayer.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.LogRedactor;
import com.baidu.carplayer.network.RetryInterceptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;

/**
 * 基于百度网盘 filemetas 接口的下载地址仓库。
 *
 * 内存缓存：下载链接有效期约 8 小时（服务器以 expires 参数声明），
 * 按 7 小时保守缓存；切歌/循环重播/预取命中缓存时零 API 往返。
 *
 * 在途去重（single-flight）：同一个 fsId 同一时刻只有一个 filemetas 请求在飞，
 * 前台播放与后台预取共用这次请求的回调。这样"预取刚好在切歌瞬间没跑完"时
 * 不会变成两个请求，预取也永远不会被切歌白取消一次。
 *
 * 预取请求使用 {@link #resolvePrefetch}：带静默标记，网络失败不重试
 * （车载弱网下 3 次退避重试会长时间占住连接与线程，反而拖慢前台加载）。
 */
public final class BaiduDownloadUrlRepository implements DownloadUrlRepository {
    private static final String TAG = "DownloadUrlRepository";
    static final String AUTH_EXPIRED_MESSAGE = "登录已失效，请重新登录";

    /** 缓存有效期：比服务器声明的 8 小时短 1 小时留安全边际。 */
    static final long CACHE_TTL_MS = 7L * 60 * 60 * 1000;
    /** 缓存上限，超出按最旧淘汰。 */
    static final int CACHE_MAX_ENTRIES = 128;

    /** 访问令牌提供者，便于测试替换。 */
    public interface TokenProvider {
        String getAccessToken();
    }

    /** 缓存条目。 */
    static final class CachedUrl {
        final String url;
        final long expiresAt;

        CachedUrl(String url, long expiresAt) {
            this.url = url;
            this.expiresAt = expiresAt;
        }
    }

    /** 同一 fsId 的在途请求；callbacks 为空则取消底层请求。 */
    private static final class PendingFetch {
        final long fsId;
        final boolean silent;
        final List<Callback> callbacks = new ArrayList<>();
        @Nullable
        Call<DownloadLinkResponse> call;
        boolean finished;

        PendingFetch(long fsId, boolean silent) {
            this.fsId = fsId;
            this.silent = silent;
        }
    }

    private final BaiduPanService panService;
    private final TokenProvider tokenProvider;
    /** fsId → 可播放 URL；插入序访问即 LRU 序。 */
    private final LinkedHashMap<Long, CachedUrl> cache = new LinkedHashMap<>();
    /** fsId → 在途请求。 */
    private final Map<Long, PendingFetch> pending = new HashMap<>();
    private String cachedToken;

    public BaiduDownloadUrlRepository(BaiduPanService panService, BaiduAuthService authService) {
        this(panService, authService::getAccessToken);
    }

    public BaiduDownloadUrlRepository(BaiduPanService panService, TokenProvider tokenProvider) {
        this.panService = panService;
        this.tokenProvider = tokenProvider;
    }

    @Override
    public Cancellable resolve(long fsId, Callback callback) {
        return resolveInternal(fsId, callback, false);
    }

    @Override
    public Cancellable resolvePrefetch(long fsId, Callback callback) {
        return resolveInternal(fsId, callback, true);
    }

    private Cancellable resolveInternal(long fsId, Callback callback, boolean silent) {
        String accessToken = tokenProvider.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            callback.onError(AUTH_EXPIRED_MESSAGE, true, null);
            return () -> { };
        }
        CachedUrl hit = cacheGetValid(accessToken, fsId);
        if (hit != null) {
            // 缓存命中：同步回调（调用方在主线程，无切换问题）
            callback.onSuccess(hit.url);
            return () -> { };
        }

        PendingFetch fetch;
        boolean startNow = false;
        synchronized (pending) {
            fetch = pending.get(fsId);
            if (fetch == null) {
                fetch = new PendingFetch(fsId, silent);
                pending.put(fsId, fetch);
                startNow = true;
            }
            fetch.callbacks.add(callback);
        }
        final PendingFetch target = fetch;
        if (startNow) {
            start(target, accessToken);
        }
        return () -> detach(target, callback);
    }

    /** 命中且未过期则刷新 LRU 序并返回；否则移除失效条目并返回 null。 */
    private CachedUrl cacheGetValid(String accessToken, long fsId) {
        synchronized (cache) {
            if (!accessToken.equals(cachedToken)) {
                cache.clear();
                cachedToken = accessToken;
            }
            CachedUrl entry = cache.get(fsId);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAt <= System.currentTimeMillis()) {
                cache.remove(fsId);
                return null;
            }
            cache.remove(fsId);
            cache.put(fsId, entry);
            return entry;
        }
    }

    @Override
    public String getCachedUrl(long fsId) {
        String token = tokenProvider.getAccessToken();
        if (token == null || token.isEmpty()) {
            return null;
        }
        synchronized (cache) {
            if (!token.equals(cachedToken)) {
                return null;
            }
            CachedUrl entry = cache.get(fsId);
            if (entry == null || entry.expiresAt <= System.currentTimeMillis()) {
                return null;
            }
            return entry.url;
        }
    }

    @Override
    public void evict(long fsId) {
        synchronized (cache) {
            cache.remove(fsId);
        }
    }

    private void cachePut(long fsId, String url) {
        synchronized (cache) {
            cache.remove(fsId);
            cache.put(fsId, new CachedUrl(url, System.currentTimeMillis() + CACHE_TTL_MS));
            while (cache.size() > CACHE_MAX_ENTRIES) {
                Iterator<Long> it = cache.keySet().iterator();
                Long oldest = it.next();
                it.remove();
            }
        }
    }

    private void start(final PendingFetch fetch, final String accessToken) {
        final long startedAt = System.currentTimeMillis();
        Call<DownloadLinkResponse> call = panService.getFileDownloadLink(
                "filemetas", accessToken, "[" + fetch.fsId + "]", 1,
                fetch.silent ? RetryInterceptor.SILENT_REQUEST : null);
        synchronized (pending) {
            if (fetch.finished) {
                call.cancel();
                return;
            }
            fetch.call = call;
        }
        call.enqueue(new retrofit2.Callback<DownloadLinkResponse>() {
            @Override
            public void onResponse(@NonNull Call<DownloadLinkResponse> c,
                                   @NonNull Response<DownloadLinkResponse> response) {
                if (c.isCanceled()) return;
                deliver(response, accessToken, fetch, startedAt);
            }

            @Override
            public void onFailure(@NonNull Call<DownloadLinkResponse> c, @NonNull Throwable t) {
                if (c.isCanceled()) return;
                Log.e(TAG, "获取下载链接网络请求失败: " + LogRedactor.redact(t.getMessage()));
                fail(fetch, "网络请求失败: " + LogRedactor.redact(t.getMessage()), false, t, startedAt);
            }
        });
    }

    private void deliver(Response<DownloadLinkResponse> response, String accessToken,
                         PendingFetch fetch, long startedAt) {
        if (!response.isSuccessful() || response.body() == null) {
            boolean auth = response.code() == 401 || response.code() == 403;
            fail(fetch, auth ? AUTH_EXPIRED_MESSAGE : "API响应失败", auth, null, startedAt);
            return;
        }
        DownloadLinkResponse body = response.body();
        if (body.getErrno() != 0 || body.getList() == null || body.getList().isEmpty()
                || body.getList().get(0).getDlink() == null) {
            boolean auth = isAuthError(body.getErrno());
            fail(fetch, auth ? AUTH_EXPIRED_MESSAGE : "获取下载链接失败", auth, null, startedAt);
            return;
        }
        String url = appendAccessToken(body.getList().get(0).getDlink(), accessToken);
        cachePut(fetch.fsId, url);
        if (!fetch.silent) {
            Log.d(TAG, "下载链接解析完成 fsId=" + fetch.fsId
                    + "，耗时 " + (System.currentTimeMillis() - startedAt) + "ms");
        }
        List<Callback> snapshot = takeCallbacks(fetch);
        for (Callback callback : snapshot) {
            callback.onSuccess(url);
        }
    }

    private void fail(PendingFetch fetch, String message, boolean authExpired,
                      @Nullable Throwable cause, long startedAt) {
        if (!fetch.silent) {
            Log.w(TAG, "下载链接解析失败 fsId=" + fetch.fsId + "，耗时 "
                    + (System.currentTimeMillis() - startedAt) + "ms：" + message);
        }
        List<Callback> snapshot = takeCallbacks(fetch);
        for (Callback callback : snapshot) {
            callback.onError(message, authExpired, cause);
        }
    }

    /** 结束这次在途请求并取出全部回调；返回后该 fsId 可以重新发起请求。 */
    private List<Callback> takeCallbacks(PendingFetch fetch) {
        synchronized (pending) {
            fetch.finished = true;
            if (pending.get(fetch.fsId) == fetch) {
                pending.remove(fetch.fsId);
            }
            List<Callback> snapshot = new ArrayList<>(fetch.callbacks);
            fetch.callbacks.clear();
            return snapshot;
        }
    }

    /** 调用方取消：仅移除自己的回调，最后一个回调移除时才真正取消网络请求。 */
    private void detach(PendingFetch fetch, Callback callback) {
        Call<DownloadLinkResponse> toCancel = null;
        synchronized (pending) {
            if (fetch.finished) {
                return;
            }
            fetch.callbacks.remove(callback);
            if (fetch.callbacks.isEmpty()) {
                fetch.finished = true;
                if (pending.get(fetch.fsId) == fetch) {
                    pending.remove(fetch.fsId);
                }
                toCancel = fetch.call;
            }
        }
        if (toCancel != null) {
            toCancel.cancel();
        }
    }

    static String appendAccessToken(String url, String accessToken) {
        return url + (url.contains("?") ? "&" : "?") + "access_token=" + accessToken;
    }

    /** 百度网盘常见的 token 失效错误码。 */
    static boolean isAuthError(int errno) {
        return errno == 110 || errno == 111 || errno == 31034;
    }
}
