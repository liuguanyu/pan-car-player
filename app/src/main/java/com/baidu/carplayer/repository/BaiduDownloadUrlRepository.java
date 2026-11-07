package com.baidu.carplayer.repository;

import android.util.Log;

import androidx.annotation.NonNull;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.model.DownloadLinkResponse;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.LogRedactor;

import retrofit2.Call;
import retrofit2.Response;

/** 基于百度网盘 filemetas 接口的下载地址仓库。 */
public final class BaiduDownloadUrlRepository implements DownloadUrlRepository {
    private static final String TAG = "DownloadUrlRepository";
    static final String AUTH_EXPIRED_MESSAGE = "登录已失效，请重新登录";

    /** 访问令牌提供者，便于测试替换。 */
    public interface TokenProvider {
        String getAccessToken();
    }

    private final BaiduPanService panService;
    private final TokenProvider tokenProvider;

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
        // 百度网盘 API 要求 fsids 为 JSON 数组，且必须带 dlink=1 才返回下载链接。
        Call<DownloadLinkResponse> call = panService.getFileDownloadLink(
                "filemetas", accessToken, "[" + fsId + "]", 1);
        call.enqueue(new retrofit2.Callback<DownloadLinkResponse>() {
            @Override
            public void onResponse(@NonNull Call<DownloadLinkResponse> c,
                                   @NonNull Response<DownloadLinkResponse> response) {
                if (c.isCanceled()) return;
                deliver(response, accessToken, callback);
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

    static void deliver(Response<DownloadLinkResponse> response, String accessToken, Callback callback) {
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
        String dlink = body.getList().get(0).getDlink();
        // 百度网盘下载链接需要附加 access_token；该 URL 不得写入日志。
        callback.onSuccess(appendAccessToken(dlink, accessToken));
    }

    static String appendAccessToken(String url, String accessToken) {
        return url + (url.contains("?") ? "&" : "?") + "access_token=" + accessToken;
    }

    /** 百度网盘常见的 token 失效错误码。 */
    static boolean isAuthError(int errno) {
        return errno == 110 || errno == 111 || errno == 31034;
    }
}
