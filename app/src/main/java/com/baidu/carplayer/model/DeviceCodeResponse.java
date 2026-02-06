package com.baidu.carplayer.model;

import com.google.gson.annotations.SerializedName;

/**
 * 设备码响应模型
 */
public class DeviceCodeResponse {
    
    @SerializedName("device_code")
    private String deviceCode;
    
    @SerializedName("user_code")
    private String userCode;
    
    @SerializedName("verification_url")
    private String verificationUrl;
    
    @SerializedName("expires_in")
    private long expiresIn;
    
    @SerializedName("interval")
    private int interval;
    
    @SerializedName("error")
    private String error;
    
    @SerializedName("error_description")
    private String errorDescription;

    // Getters and Setters
    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }

    public String getVerificationUrl() {
        return verificationUrl;
    }

    public void setVerificationUrl(String verificationUrl) {
        this.verificationUrl = verificationUrl;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public int getInterval() {
        return interval;
    }

    public void setInterval(int interval) {
        this.interval = interval;
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
    
    /**
     * 获取完整的验证URL
     */
    public String getFullVerificationUrl() {
        if (verificationUrl == null || userCode == null) {
            return null;
        }
        return verificationUrl + "?code=" + userCode;
    }
}