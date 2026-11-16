package com.baidu.carplayer.repository;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.LogRedactor;

import java.util.Iterator;
import java.util.LinkedHashMap;

import retrofit2.Call;
import retrofit2.Response;

/**
 * 基于百度网盘 filemetas 接口的下载地址仓库。
 *
 * 内存缓存：下载链接有效期约 8 小时（服务器以 expires 参数声明），
 * 按 7 小时保守缓存；切歌/循环重播/预取命中缓存时零 API 往返。
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

    private final BaiduPanService panService;
    private final TokenProvider tokenProvider;
    /** fsId → 可播放 URL；插入序访问即 LRU 序。 */
    private final LinkedHashMap<Long, CachedUrl> cache = new LinkedHashMap<>();
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
        String accessToken = tokenProvider.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            callback.onError(AUTH_EXPIRED_MESSAGE, true, null);
            return () -> { };
        }
        CachedUrl hit = cacheGetValid(accessToken, fsId);
        if (hit != null) {
            // 缓存命中：同步回调（调用方在 Service 主线程，无切换问题）
            callback.onSuccess(hit.url);
            return () -> { };
        }
        return fetchAndCache(fsId, accessToken, callback);
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

    private Cancellable fetchAndCache(final long fsId, final String accessToken, final Callback callback) {
        Call<DownloadLinkResponse> call = panService.getFileDownloadLink(
                "filemetas", accessToken, "[" + fsId + "]", 1);
        call.enqueue(new retrofit2.Callback<DownloadLinkResponse>() {
            @Override
            public void onResponse(@NonNull Call<DownloadLinkResponse> c,
                                   @NonNull Response<DownloadLinkResponse> response) {
                if (c.isCanceled()) return;
                deliver(response, accessToken, callback, fsId);
            }

            @Override
            public void onFailure(@NonNull Call<DownloadLinkResponse> c, @NonNull Throwable t) {
                if (c.isCanceled()) return;
                Log.e(TAG, "获取下载链接网络请求失败: " + LogRedactor.redact(t.getMessage()));
                callback.onError("网络请求失败: " + LogRedactor.redact(t.getMessage()), false, t);
            }
        });
        return call::cancel;
    }

    private void deliver(Response<DownloadLinkResponse> response, String accessToken,
                         Callback callback, long fsId) {
        if (!response.isSuccessful() || response.body() == null) {
            boolean auth = response.code() == 401 || response.code() == 403;
            callback.onError(auth ? AUTH_EXPIRED_MESSAGE : "API响应失败", auth, null);
            return;
        }
        DownloadLinkResponse body = response.body();
        if (body.getErrno() != 0 || body.getList() == null || body.getList().isEmpty()
                || body.getList().get(0).getDlink() == null) {
            boolean auth = isAuthError(body.getErrno());
            callback.onError(auth ? AUTH_EXPIRED_MESSAGE : "获取下载链接失败", auth, null);
            return;
        }
        String url = appendAccessToken(body.getList().get(0).getDlink(), accessToken);
        cachePut(fsId, url);
        callback.onSuccess(url);
    }

    static String appendAccessToken(String url, String accessToken) {
        return url + (url.contains("?") ? "&" : "?") + "access_token=" + accessToken;
    }

    /** 百度网盘常见的 token 失效错误码。 */
    static boolean isAuthError(int errno) {
        return errno == 110 || errno == 111 || errno == 31034;
    }
}
