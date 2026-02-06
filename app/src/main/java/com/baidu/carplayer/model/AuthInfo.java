package com.baidu.carplayer.model;

/**
 * 认证信息模型
 */
public class AuthInfo {
    private String accessToken;
    private String refreshToken;
    private long expiresAt;
    private String scope;
    private String sessionSecret;
    private String sessionKey;
    private long sessionExpiresAt;
    private String userId;
    private String username;
    private boolean isLoggedIn;

    public AuthInfo() {
        this.isLoggedIn = false;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSessionSecret() {
        return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public long getSessionExpiresAt() {
        return sessionExpiresAt;
    }

    public void setSessionExpiresAt(long sessionExpiresAt) {
        this.sessionExpiresAt = sessionExpiresAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public void setLoggedIn(boolean loggedIn) {
        isLoggedIn = loggedIn;
    }

    /**
     * 检查token是否过期
     */
    public boolean isTokenExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}