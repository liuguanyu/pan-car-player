package com.baidu.carplayer.model;

import com.google.gson.annotations.SerializedName;

/**
 * Token响应模型
 */
public class TokenResponse {
    
    @SerializedName("access_token")
    private String accessToken;
    
    @SerializedName("refresh_token")
    private String refreshToken;
    
    @SerializedName("expires_in")
    private long expiresIn;
    
    @SerializedName("scope")
    private String scope;
    
    @SerializedName("session_key")
    private String sessionKey;
    
    @SerializedName("session_secret")
    private String sessionSecret;
    
    @SerializedName("error")
    private String error;
    
    @SerializedName("error_description")
    private String errorDescription;

    // Getters and Setters
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

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getSessionSecret() {
        return sessionSecret;
    }

    public void setSessionSecret(String sessionSecret) {
        this.sessionSecret = sessionSecret;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getErrorDescription() {
        return errorDescription;
    }

    public void setErrorDescription(String errorDescription) {
        this.errorDescription = errorDescription;
    }

    /**
     * 是否有错误
     */
    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}