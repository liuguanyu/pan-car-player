package com.baidu.carplayer.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.baidu.carplayer.model.AuthInfo;
import com.baidu.carplayer.model.TokenResponse;

import org.junit.Before;
import org.junit.Test;

public class AuthStoreTest {
    private AuthInfo persisted;
    private int saves;
    private long now = 1_000L;
    private AuthStore store;

    @Before
    public void setUp() {
        persisted = new AuthInfo();
        persisted.setRefreshToken("old-refresh");
        persisted.setSessionKey("old-session");
        store = newStore();
    }

    private AuthStore newStore() {
        return new AuthStore(new AuthStore.Persistence() {
            @Override
            public AuthInfo load() {
                return persisted;
            }

            @Override
            public void save(AuthInfo info) {
                persisted = info;
                saves++;
            }
        }, () -> now);
    }

    private static TokenResponse token(String access, String refresh) {
        TokenResponse t = new TokenResponse();
        t.setAccessToken(access);
        t.setRefreshToken(refresh);
        t.setExpiresIn(10);
        return t;
    }

    @Test
    public void refreshWithoutNewRefreshTokenKeepsOldOne() {
        assertTrue(store.applyToken(store.generation(), token("a1", null)));
        AuthInfo info = store.snapshot();
        assertEquals("a1", info.getAccessToken());
        assertEquals("old-refresh", info.getRefreshToken());
        assertEquals("old-session", info.getSessionKey());
        assertEquals(11_000L, info.getExpiresAt());
        assertTrue(store.isAuthenticated());
        assertEquals("a1", persisted.getAccessToken());
    }

    @Test
    public void staleResponseAfterLogoutIsIgnored() {
        long gen = store.generation();
        store.clear();
        assertFalse(store.applyToken(gen, token("late", "r")));
        assertFalse(store.isAuthenticated());
        assertEquals("", nullToEmpty(persisted.getAccessToken()));
    }

    @Test
    public void staleResponseAfterCancelIsIgnored() {
        long gen = store.generation();
        store.invalidate();
        assertFalse(store.applyToken(gen, token("late", "r")));
        assertEquals(0, saves);
    }

    @Test
    public void onlyFirstConcurrentResponseWins() {
        long gen = store.generation();
        assertTrue(store.applyToken(gen, token("first", "r1")));
        assertFalse(store.applyToken(gen, token("second", "r2")));
        assertEquals("first", store.getAccessToken());
    }

    @Test
    public void invalidTokenDoesNotWrite() {
        assertFalse(store.applyToken(store.generation(), token("", "r")));
        assertEquals(0, saves);
    }

    @Test
    public void snapshotIsDefensiveCopy() {
        store.snapshot().setAccessToken("hacked");
        assertEquals(null, store.getAccessToken());
    }

    @Test
    public void expiryIsEvaluatedWithClock() {
        store.applyToken(store.generation(), token("a", "r"));
        now = 11_000L;
        assertFalse(store.isAuthenticated());
    }

    @Test
    public void stateSurvivesReload() {
        store.applyToken(store.generation(), token("a", "r"));
        AuthStore reloaded = newStore();
        assertEquals("a", reloaded.getAccessToken());
        assertEquals("r", reloaded.getRefreshToken());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
