package com.baidu.carplayer.auth;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.TokenResponse;

/**
 * 设备码轮询单次响应的判定结果（纯逻辑，便于单测）。
 * 百度在 pending 状态返回 HTTP 400 + error=authorization_pending。
 */
public final class TokenPollResult {
    public enum Kind { SUCCESS, PENDING, SLOW_DOWN, EXPIRED, FAILED, STALE }

    public final Kind kind;
    @Nullable
    public final String message;

    private TokenPollResult(Kind kind, @Nullable String message) {
        this.kind = kind;
        this.message = message;
    }

    /**
     * 根据响应和认证存储判定结果；SUCCESS 时令牌已经写入 store。
     * @param errorBody HTTP 失败时的响应体文本，可为 null
     */
    public static TokenPollResult evaluate(AuthStore store, long requestGeneration, boolean httpOk,
                                           int httpCode, @Nullable TokenResponse body,
                                           @Nullable String errorBody) {
        if (requestGeneration != store.generation()) {
            return new TokenPollResult(Kind.STALE, null);
        }
        if (httpOk && body != null) {
            if (store.applyToken(requestGeneration, body)) {
                return new TokenPollResult(Kind.SUCCESS, null);
            }
            return fromError(body.getError(), body.getErrorDescription());
        }
        String error = null;
        if (errorBody != null) {
            if (errorBody.contains("authorization_pending")) error = "authorization_pending";
            else if (errorBody.contains("slow_down")) error = "slow_down";
            else if (errorBody.contains("expired_token")) error = "expired_token";
        }
        if (error != null) {
            return fromError(error, null);
        }
        return new TokenPollResult(Kind.FAILED, "授权失败: " + httpCode);
    }

    /** 网络失败：仅当请求仍有效时才报告。 */
    public static TokenPollResult networkFailure(AuthStore store, long requestGeneration, @Nullable String message) {
        if (requestGeneration != store.generation()) {
            return new TokenPollResult(Kind.STALE, null);
        }
        return new TokenPollResult(Kind.FAILED, message != null ? message : "网络错误");
    }

    private static TokenPollResult fromError(@Nullable String error, @Nullable String description) {
        if ("authorization_pending".equals(error)) return new TokenPollResult(Kind.PENDING, null);
        // slow_down 按 RFC 8628 继续轮询（由调用方加大间隔）。
        if ("slow_down".equals(error)) return new TokenPollResult(Kind.SLOW_DOWN, null);
        if ("expired_token".equals(error)) return new TokenPollResult(Kind.EXPIRED, "授权已过期");
        return new TokenPollResult(Kind.FAILED, description != null ? description : "授权失败");
    }
}
