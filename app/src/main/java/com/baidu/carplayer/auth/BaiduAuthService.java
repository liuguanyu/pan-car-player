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
    // 认证状态的唯一写入口；本类只负责网络交互和持久化格式。
    private final AuthStore store;
    private final Handler handler;
    
    /** 请使用 {@link #getInstance}；多个实例会各自持有认证状态，导致登出/刷新不同步。 */
    private BaiduAuthService(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.oauthService = RetrofitClient.getOAuthInstance().create(BaiduPanService.class);
        this.handler = new Handler(Looper.getMainLooper());
        this.store = new AuthStore(new AuthStore.Persistence() {
            @Override
            public AuthInfo load() {
                return loadAuthInfo();
            }

            @Override
            public void save(AuthInfo info) {
                saveAuthInfo(info);
            }
        }, System::currentTimeMillis);
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
    private void saveAuthInfo(AuthInfo authInfo) {
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
        return store.isAuthenticated();
    }
    
    /**
     * 获取访问令牌
     */
    public String getAccessToken() {
        return store.getAccessToken();
    }
    
    /**
     * 获取认证信息
     */
    public AuthInfo getAuthInfo() {
        return store.snapshot();
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
     * 刷新token
     */
    public void refreshToken(AuthCallback<Boolean> callback) {
        String refreshToken = store.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            callback.onError("无刷新令牌");
            return;
        }
        final long requestGeneration = store.generation();
        
        Call<TokenResponse> call = oauthService.refreshToken(
                "refresh_token",
                refreshToken,
                BaiduConfig.APP_KEY,
                BaiduConfig.SECRET_KEY
        );
        
        call.enqueue(new Callback<TokenResponse>() {
            @Override
            public void onResponse(Call<TokenResponse> call, Response<TokenResponse> response) {
                TokenResponse tokenResponse = response.isSuccessful() ? response.body() : null;
                if (tokenResponse != null && store.applyToken(requestGeneration, tokenResponse)) {
                    callback.onSuccess(true);
                } else if (requestGeneration != store.generation()) {
                    callback.onError("刷新已取消");
                } else {
                    callback.onError(tokenResponse != null && tokenResponse.getErrorDescription() != null
                            ? tokenResponse.getErrorDescription() : "刷新令牌失败");
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
        handler.removeCallbacksAndMessages(null);
        store.clear();
    }

    /** 使当前设备码轮询和已发出的认证回调失效。 */
    public void cancelPolling() {
        store.invalidate();
        handler.removeCallbacksAndMessages(null);
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
        final long requestGeneration = store.generation();
        Call<TokenResponse> call = oauthService.getTokenByDeviceCode(
                "device_token",
                deviceCode,
                BaiduConfig.APP_KEY,
                BaiduConfig.SECRET_KEY
        );
        
        call.enqueue(new Callback<TokenResponse>() {
            @Override
            public void onResponse(Call<TokenResponse> call, Response<TokenResponse> response) {
                String errorBody = null;
                if (!response.isSuccessful() && response.errorBody() != null) {
                    try {
                        errorBody = response.errorBody().string();
                    } catch (java.io.IOException ignored) {
                        // 按普通 HTTP 失败处理
                    }
                }
                deliver(TokenPollResult.evaluate(store, requestGeneration, response.isSuccessful(),
                        response.code(), response.body(), errorBody), callback);
            }

            @Override
            public void onFailure(Call<TokenResponse> call, Throwable t) {
                deliver(TokenPollResult.networkFailure(store, requestGeneration, t.getMessage()), callback);
            }
        });
    }

    private void deliver(TokenPollResult result, TokenCallback callback) {
        switch (result.kind) {
            case SUCCESS:
                callback.onSuccess(store.snapshot());
                break;
            case PENDING:
            case SLOW_DOWN:
                callback.onPending();
                break;
            case EXPIRED:
            case FAILED:
                callback.onError(result.message);
                break;
            case STALE:
            default:
                break; // 已取消或已登出：静默丢弃
        }
    }
}