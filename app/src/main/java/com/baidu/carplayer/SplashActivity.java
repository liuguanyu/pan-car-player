package com.baidu.carplayer;

import android.content.Intent;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

import com.baidu.carplayer.auth.BaiduAuthService;

/**
 * 启动页Activity - 检查登录状态并跳转到相应页面
 */
public class SplashActivity extends AppCompatActivity {
    
    private static final String TAG = "SplashActivity";
    private static final int SPLASH_DELAY = 1000; // 1秒延迟
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate started");
        
        try {
            setContentView(R.layout.activity_splash);
            Log.d(TAG, "Layout set successfully");
            
            // 延迟后检查登录状态
            new Handler(Looper.getMainLooper()).postDelayed(this::checkLoginStatus, SPLASH_DELAY);
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
            finish();
        }
    }
    
    /**
     * 检查登录状态
     */
    private void checkLoginStatus() {
        Log.d(TAG, "checkLoginStatus started");
        
        try {
            // 使用BaiduAuthService检查登录状态，确保一致性
            BaiduAuthService authService = BaiduAuthService.getInstance(this);
            boolean isLoggedIn = authService.isAuthenticated();
            Log.d(TAG, "Is logged in: " + isLoggedIn);
            
            Intent intent;
            if (isLoggedIn) {
                // 检查是否有保存的播放状态
                SharedPreferences prefs = getSharedPreferences("AudioPlayerPrefs", MODE_PRIVATE);
                boolean hasSavedState = prefs.contains("playlist");
                Log.d(TAG, "Has saved state: " + hasSavedState);
                
                if (hasSavedState) {
                    // 有保存的播放状态，直接跳转到播放界面
                    intent = new Intent(this, PlayerActivity.class);
                    intent.putExtra(PlayerActivity.EXTRA_RESUME_PLAYBACK, true);
                } else {
                    // 没有保存的播放状态，跳转到主界面
                    intent = new Intent(this, MainActivity.class);
                }
            } else {
                // 未登录，跳转到登录界面
                intent = new Intent(this, LoginActivity.class);
            }
            
            Log.d(TAG, "Starting activity: " + intent.getComponent());
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error in checkLoginStatus", e);
            // 发生错误时，直接跳转到登录界面
            try {
                Intent intent = new Intent(this, LoginActivity.class);
                startActivity(intent);
                finish();
            } catch (Exception ex) {
                Log.e(TAG, "Error starting LoginActivity", ex);
                finish();
            }
        }
    }
}