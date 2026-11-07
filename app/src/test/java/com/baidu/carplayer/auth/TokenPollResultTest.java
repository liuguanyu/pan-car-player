package com.baidu.carplayer.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.AuthInfo;
import com.baidu.carplayer.model.TokenResponse;

import org.junit.Before;
import org.junit.Test;

public class TokenPollResultTest {
    private AuthStore store;

    @Before
    public void setUp() {
        store = new AuthStore(new AuthStore.Persistence() {
            AuthInfo saved = new AuthInfo();
            @Override public AuthInfo load() { return saved; }
            @Override public void save(AuthInfo info) { saved = info; }
        }, () -> 0L);
    }

    private static TokenResponse ok(String token) {
        TokenResponse t = new TokenResponse();
        t.setAccessToken(token);
        t.setRefreshToken("r");
        t.setExpiresIn(100);
        return t;
    }

    @Test
    public void successWritesTokenOnce() {
        long gen = store.generation();
        TokenPollResult r = TokenPollResult.evaluate(store, gen, true, 200, ok("a"), null);
        assertEquals(TokenPollResult.Kind.SUCCESS, r.kind);
        assertTrue(store.isAuthenticated());
        // 同一轮的第二个响应（例如重复轮询）不会覆盖
        assertEquals(TokenPollResult.Kind.STALE,
                TokenPollResult.evaluate(store, gen, true, 200, ok("b"), null).kind);
        assertEquals("a", store.getAccessToken());
    }

    @Test
    public void pendingAndSlowDownKeepPolling() {
        long gen = store.generation();
        assertEquals(TokenPollResult.Kind.PENDING, TokenPollResult.evaluate(store, gen, false, 400, null,
                "{\"error\":\"authorization_pending\"}").kind);
        assertEquals(TokenPollResult.Kind.SLOW_DOWN, TokenPollResult.evaluate(store, gen, false, 400, null,
                "{\"error\":\"slow_down\"}").kind);
        assertFalse(store.isAuthenticated());
    }

    @Test
    public void expiredAndUnknownErrorsStop() {
        long gen = store.generation();
        TokenPollResult expired = TokenPollResult.evaluate(store, gen, false, 400, null, "expired_token");
        assertEquals(TokenPollResult.Kind.EXPIRED, expired.kind);
        TokenPollResult failed = TokenPollResult.evaluate(store, gen, false, 500, null, null);
        assertEquals(TokenPollResult.Kind.FAILED, failed.kind);
        assertTrue(failed.message.contains("500"));
    }

    @Test
    public void errorFieldInSuccessfulBodyIsHonoured() {
        TokenResponse body = new TokenResponse();
        body.setError("authorization_pending");
        assertEquals(TokenPollResult.Kind.PENDING,
                TokenPollResult.evaluate(store, store.generation(), true, 200, body, null).kind);
    }

    @Test
    public void responsesAfterCancelOrLogoutAreStale() {
        long gen = store.generation();
        store.invalidate(); // 用户关闭登录页
        TokenPollResult r = TokenPollResult.evaluate(store, gen, true, 200, ok("late"), null);
        assertEquals(TokenPollResult.Kind.STALE, r.kind);
        assertNull(store.getAccessToken());

        long gen2 = store.generation();
        store.clear(); // 登出
        assertEquals(TokenPollResult.Kind.STALE, TokenPollResult.networkFailure(store, gen2, "x").kind);
    }

    @Test
    public void networkFailureReportedWhileCurrent() {
        TokenPollResult r = TokenPollResult.networkFailure(store, store.generation(), null);
        assertEquals(TokenPollResult.Kind.FAILED, r.kind);
        assertEquals("网络错误", r.message);
    }
}
