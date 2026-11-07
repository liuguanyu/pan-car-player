package com.baidu.carplayer.auth;

import androidx.annotation.Nullable;

import com.baidu.carplayer.model.AuthInfo;
import com.baidu.carplayer.model.TokenResponse;

/**
 * 认证状态的唯一持有者和唯一写入口。
 * <ul>
 *   <li>读：{@link #snapshot()} 总是返回副本，调用方修改不会影响内部状态；</li>
 *   <li>写：只能通过 {@link #applyToken}/{@link #clear}，带代次校验，过期回调无法覆盖新状态；</li>
 *   <li>持久化：通过 {@link Persistence} 抽象，便于在 JVM 单元测试中替换。</li>
 * </ul>
 */
public final class AuthStore {

    public interface Persistence {
        AuthInfo load();

        void save(AuthInfo info);
    }

    public interface Clock {
        long now();
    }

    private final Persistence persistence;
    private final Clock clock;
    private AuthInfo current;
    private long generation;

    public AuthStore(Persistence persistence, Clock clock) {
        this.persistence = persistence;
        this.clock = clock;
        AuthInfo loaded = persistence.load();
        this.current = loaded != null ? new AuthInfo(loaded) : new AuthInfo();
    }

    public synchronized AuthInfo snapshot() {
        return new AuthInfo(current);
    }

    @Nullable
    public synchronized String getAccessToken() {
        return current.getAccessToken();
    }

    @Nullable
    public synchronized String getRefreshToken() {
        return current.getRefreshToken();
    }

    /** 当前代次；发起网络请求前记录，回调时传回以检测是否已过期。 */
    public synchronized long generation() {
        return generation;
    }

    /** 使所有进行中的认证请求失效（取消轮询、登出前调用）。 */
    public synchronized long invalidate() {
        return ++generation;
    }

    public synchronized boolean isAuthenticated() {
        String token = current.getAccessToken();
        return current.isLoggedIn() && token != null && !token.isEmpty()
                && clock.now() < current.getExpiresAt();
    }

    /**
     * 应用设备码授权或刷新令牌的结果。
     * 响应缺少 refresh_token 或会话字段时保留原值。
     * @return false 表示请求已过期（期间发生了登出/取消/其他写入）或响应无效，状态未改变
     */
    public synchronized boolean applyToken(long requestGeneration, TokenResponse token) {
        if (requestGeneration != generation || token == null
                || token.getAccessToken() == null || token.getAccessToken().isEmpty()) {
            return false;
        }
        AuthInfo next = new AuthInfo(current);
        next.setAccessToken(token.getAccessToken());
        if (notEmpty(token.getRefreshToken())) next.setRefreshToken(token.getRefreshToken());
        next.setExpiresAt(clock.now() + token.getExpiresIn() * 1000L);
        if (notEmpty(token.getScope())) next.setScope(token.getScope());
        if (notEmpty(token.getSessionKey())) next.setSessionKey(token.getSessionKey());
        if (notEmpty(token.getSessionSecret())) next.setSessionSecret(token.getSessionSecret());
        next.setLoggedIn(true);
        commit(next);
        return true;
    }

    /** 登出：清空认证信息并使所有进行中的请求失效。 */
    public synchronized void clear() {
        generation++;
        commit(new AuthInfo());
    }

    private void commit(AuthInfo next) {
        current = next;
        // 每次成功写入都推进代次，同一代次的其他并发响应不会再覆盖它。
        generation++;
        persistence.save(new AuthInfo(next));
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }
}
