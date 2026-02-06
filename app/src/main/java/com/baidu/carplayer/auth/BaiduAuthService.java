package com.baidu.carplayer.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.baidu.carplayer.config.BaiduConfig;
import com.baidu.carplayer.model.AuthInfo;
import com.baidu.carplayer.model.DeviceCodeResponse;
import com.baidu.carplayer.model.TokenResponse;
import com.baidu.carplayer.network.ApiConstants;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;

import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 百度网盘认证服务
 * 针对车载环境进行了优化
 */
public class BaiduAuthService {
    
    private static final String PREF_NAME = "baidu_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_SCOPE = "scope";
    private static final String KEY_SESSION_KEY = "session_key";
    private static final String KEY_SESSION_SECRET = "session_secret";
    private static final String KEY_SESSION_EXPIRES_AT = "session_expires_at";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_DEVICE_ID = "device_id";
    
    private static volatile BaiduAuthService instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final BaiduPanService oauthService;
    private AuthInfo authInfo;
    private Handler handler;
    
    public BaiduAuthService(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.oauthService = RetrofitClient.getOAuthInstance().create(BaiduPanService.class);
        this.handler = new Handler(Looper.getMainLooper());
        this.authInfo = loadAuthInfo();
    }
    
    public static BaiduAuthService getInstance(Context context) {
        if (instance == null) {
            synchronized (BaiduAuthService.class) {
                if (instance == null) {
                    instance = new BaiduAuthService(context);
                }
            }
        }
        return instance;
    }
    
    /**
     * 从SharedPreferences加载认证信息
     */
    private AuthInfo loadAuthInfo() {
        AuthInfo info = new AuthInfo();
        info.setAccessToken(prefs.getString(KEY_ACCESS_TOKEN, ""));
        info.setRefreshToken(prefs.getString(KEY_REFRESH_TOKEN, ""));
        info.setExpiresAt(prefs.getLong(KEY_EXPIRES_AT, 0));
        info.setScope(prefs.getString(KEY_SCOPE, ""));
        info.setSessionKey(prefs.getString(KEY_SESSION_KEY, ""));
        info.setSessionSecret(prefs.getString(KEY_SESSION_SECRET, ""));
        info.setSessionExpiresAt(prefs.getLong(KEY_SESSION_EXPIRES_AT, 0));
        info.setUserId(prefs.getString(KEY_USER_ID, ""));
        info.setUsername(prefs.getString(KEY_USERNAME, ""));
        info.setLoggedIn(prefs.getBoolean(KEY_IS_LOGGED_IN, false));
        return info;
    }
    
    /**
     * 保存认证信息到SharedPreferences
     */
    private void saveAuthInfo() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_ACCESS_TOKEN, authInfo.getAccessToken());
        editor.putString(KEY_REFRESH_TOKEN, authInfo.getRefreshToken());
        editor.putLong(KEY_EXPIRES_AT, authInfo.getExpiresAt());
        editor.putString(KEY_SCOPE, authInfo.getScope());
        editor.putString(KEY_SESSION_KEY, authInfo.getSessionKey());
        editor.putString(KEY_SESSION_SECRET, authInfo.getSessionSecret());
        editor.putLong(KEY_SESSION_EXPIRES_AT, authInfo.getSessionExpiresAt());
        editor.putString(KEY_USER_ID, authInfo.getUserId());
        editor.putString(KEY_USERNAME, authInfo.getUsername());
        editor.putBoolean(KEY_IS_LOGGED_IN, authInfo.isLoggedIn());
        editor.apply();
    }
    
    /**
     * 获取或生成设备ID
     */
    public String getDeviceId() {
        String deviceId = prefs.getString(KEY_DEVICE_ID, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
        return deviceId;
    }
    
    /**
     * 检查是否已认证
     */
    public boolean isAuthenticated() {
        if (!authInfo.isLoggedIn()) {
            return false;
        }
        
        if (authInfo.getAccessToken() == null || authInfo.getAccessToken().isEmpty()) {
            return false;
        }
        
        // 检查token是否过期
        if (System.currentTimeMillis() >= authInfo.getExpiresAt()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取访问令牌
     */
    public String getAccessToken() {
        return authInfo.getAccessToken();
    }
    
    /**
     * 获取认证信息
     */
    public AuthInfo getAuthInfo() {
        return authInfo;
    }
    
    /**
     * 获取设备码
     */
    public void getDeviceCode(AuthCallback<DeviceCodeResponse> callback) {
        Call<DeviceCodeResponse> call = oauthService.getDeviceCode(
                BaiduConfig.APP_KEY,
                BaiduConfig.SCOPE,
                "device_code"
        );
        
        call.enqueue(new Callback<DeviceCodeResponse>() {
            @Override
            public void onResponse(Call<DeviceCodeResponse> call, Response<DeviceCodeResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    DeviceCodeResponse deviceCodeResponse = response.body();
                    if (deviceCodeResponse.hasError()) {
                        callback.onError(deviceCodeResponse.getError());
                    } else {
                        callback.onSuccess(deviceCodeResponse);
                    }
                } else {
                    callback.onError("获取设备码失败");
                }
            }
            
            @Override
            public void onFailure(Call<DeviceCodeResponse> call, Throwable t) {
                callback.onError(t.getMessage());
            }
        });
    }
    
    /**
     * 轮询设备码状态
     */
    public void pollDeviceCodeStatus(String deviceCode, AuthCallback<Boolean> callback) {
        pollDeviceCodeStatus(deviceCode, 0, callback);
    }
    
    private void pollDeviceCodeStatus(String deviceCode, int count, AuthCallback<Boolean> callback) {
        if (count >= ApiConstants.MAX_POLLING_COUNT) {
            callback.onError("授权超时");
            return;
        }
        
        Call<TokenResponse> call = oauthService.getTokenByDeviceCode(
                "device_token",
                deviceCode,
                BaiduConfig.APP_KEY,
                BaiduConfig.SECRET_KEY
        );
        
        call.enqueue(new Callback<TokenResponse>() {
            @Override
            public void onResponse(Call<TokenResponse> call, Response<TokenResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    TokenResponse tokenResponse = response.body();
                    
                    // 授权成功
                    if (tokenResponse.getAccessToken() != null && !tokenResponse.getAccessToken().isEmpty()) {
                        authInfo.setAccessToken(tokenResponse.getAccessToken());
                        authInfo.setRefreshToken(tokenResponse.getRefreshToken());
                        authInfo.setExpiresAt(System.currentTimeMillis() + tokenResponse.getExpiresIn() * 1000);
                        authInfo.setScope(tokenResponse.getScope());
                        authInfo.setSessionKey(tokenResponse.getSessionKey());
                        authInfo.setSessionSecret(tokenResponse.getSessionSecret());
                        authInfo.setLoggedIn(true);
                        saveAuthInfo();
                        callback.onSuccess(true);
                    } else {
                        // 理论上成功响应不应该包含错误，但为了健壮性保留处理
                        handleAuthError(tokenResponse.getError(), tokenResponse.getErrorDescription(), deviceCode, count, callback);
                    }
                } else {
                    // 处理4xx错误，Baidu API在pending状态会返回400
                    try {
                        String errorBody = response.errorBody().string();
                        // 简单的JSON解析，避免引入额外依赖
                        if (errorBody.contains("authorization_pending")) {
                            handler.postDelayed(() -> {
                                pollDeviceCodeStatus(deviceCode, count + 1, callback);
                            }, ApiConstants.POLLING_INTERVAL);
                        } else if (errorBody.contains("expired_token")) {
                            callback.onError("授权已过期");
                        } else {
                            callback.onError("授权失败: " + response.code());
                        }
                    } catch (Exception e) {
                        callback.onError("授权失败: " + e.getMessage());
                    }
                }
            }

            private void handleAuthError(String error, String description, String deviceCode, int count, AuthCallback<Boolean> callback) {
                // 授权中
                if ("authorization_pending".equals(error)) {
                    handler.postDelayed(() -> {
                        pollDeviceCodeStatus(deviceCode, count + 1, callback);
                    }, ApiConstants.POLLING_INTERVAL);
                }
                // 授权过期
                else if ("expired_token".equals(error)) {
                    callback.onError("授权已过期");
                }
                // 其他错误
                else {
                    callback.onError(description != null ? description : "授权失败");
                }
            }
            
            @Override
            public void onFailure(Call<TokenResponse> call, Throwable t) {
                callback.onError(t.getMessage());
            }
        });
    }
    
    /**
     * 刷新token
     */
    public void refreshToken(AuthCallback<Boolean> callback) {
        if (authInfo.getRefreshToken() == null || authInfo.getRefreshToken().isEmpty()) {
            callback.onError("无刷新令牌");
            return;
        }
        
        Call<TokenResponse> call = oauthService.refreshToken(
                "refresh_token",
                authInfo.getRefreshToken(),
                BaiduConfig.APP_KEY,
                BaiduConfig.SECRET_KEY
        );
        
        call.enqueue(new Callback<TokenResponse>() {
            @Override
            public void onResponse(Call<TokenResponse> call, Response<TokenResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    TokenResponse tokenResponse = response.body();
                    
                    if (tokenResponse.getAccessToken() != null && !tokenResponse.getAccessToken().isEmpty()) {
                        authInfo.setAccessToken(tokenResponse.getAccessToken());
                        authInfo.setRefreshToken(tokenResponse.getRefreshToken());
                        authInfo.setExpiresAt(System.currentTimeMillis() + tokenResponse.getExpiresIn() * 1000);
                        authInfo.setScope(tokenResponse.getScope());
                        authInfo.setLoggedIn(true);
                        saveAuthInfo();
                        callback.onSuccess(true);
                    } else {
                        callback.onError(tokenResponse.getErrorDescription() != null ? 
                                tokenResponse.getErrorDescription() : "刷新令牌失败");
                    }
                } else {
                    callback.onError("刷新令牌失败");
                }
            }
            
            @Override
            public void onFailure(Call<TokenResponse> call, Throwable t) {
                callback.onError(t.getMessage());
            }
        });
    }
    
    /**
     * 退出登录
     */
    public void logout() {
        authInfo = new AuthInfo();
        saveAuthInfo();
    }
    
    /**
     * 认证回调接口
     */
    public interface AuthCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }
    
    /**
     * 设备码回调接口（兼容旧代码）
     */
    public interface DeviceCodeCallback {
        void onSuccess(String deviceCode, String userCode, String verificationUrl, int expiresIn);
        void onError(String error);
    }
    
    /**
     * Token回调接口（兼容旧代码）
     */
    public interface TokenCallback {
        void onSuccess(AuthInfo authInfo);
        void onPending();
        void onError(String error);
    }
    
    /**
     * 获取设备码（兼容旧代码）
     */
    public void getDeviceCode(DeviceCodeCallback callback) {
        getDeviceCode(new AuthCallback<DeviceCodeResponse>() {
            @Override
            public void onSuccess(DeviceCodeResponse result) {
                callback.onSuccess(
                    result.getDeviceCode(),
                    result.getUserCode(),
                    result.getVerificationUrl(),
                    (int) result.getExpiresIn()
                );
            }
            
            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }
    
    /**
     * 轮询Token（兼容旧代码）
     */
    public void pollToken(String deviceCode, TokenCallback callback) {
        Call<TokenResponse> call = oauthService.getTokenByDeviceCode(
                "device_token",
                deviceCode,
                BaiduConfig.APP_KEY,
                BaiduConfig.SECRET_KEY
        );
        
        call.enqueue(new Callback<TokenResponse>() {
            @Override
            public void onResponse(Call<TokenResponse> call, Response<TokenResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    TokenResponse tokenResponse = response.body();
                    
                    // 授权成功
                    if (tokenResponse.getAccessToken() != null && !tokenResponse.getAccessToken().isEmpty()) {
                        authInfo.setAccessToken(tokenResponse.getAccessToken());
                        authInfo.setRefreshToken(tokenResponse.getRefreshToken());
                        authInfo.setExpiresAt(System.currentTimeMillis() + tokenResponse.getExpiresIn() * 1000);
                        authInfo.setScope(tokenResponse.getScope());
                        authInfo.setSessionKey(tokenResponse.getSessionKey());
                        authInfo.setSessionSecret(tokenResponse.getSessionSecret());
                        authInfo.setLoggedIn(true);
                        saveAuthInfo();
                        callback.onSuccess(authInfo);
                    } else {
                        handleAuthError(tokenResponse.getError(), tokenResponse.getErrorDescription(), callback);
                    }
                } else {
                    // 处理4xx错误，Baidu API在pending状态会返回400
                    try {
                        String errorBody = response.errorBody().string();
                        // 简单的JSON解析，避免引入额外依赖
                        if (errorBody.contains("authorization_pending")) {
                            callback.onPending();
                        } else if (errorBody.contains("expired_token")) {
                            callback.onError("授权已过期");
                        } else {
                            callback.onError("授权失败: " + response.code());
                        }
                    } catch (Exception e) {
                        callback.onError("授权失败: " + e.getMessage());
                    }
                }
            }

            private void handleAuthError(String error, String description, TokenCallback callback) {
                // 授权中
                if ("authorization_pending".equals(error)) {
                    callback.onPending();
                }
                // 授权过期
                else if ("expired_token".equals(error)) {
                    callback.onError("授权已过期");
                }
                // 其他错误
                else {
                    callback.onError(description != null ? description : "授权失败");
                }
            }
            
            @Override
            public void onFailure(Call<TokenResponse> call, Throwable t) {
                callback.onError(t.getMessage());
            }
        });
    }
}