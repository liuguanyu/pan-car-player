package com.baidu.carplayer;

import android.content.Intent;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;

import com.baidu.carplayer.auth.BaiduAuthService;

/**
 * 启动页Activity - 检查登录状态并跳转到相应页面
 */
public class SplashActivity extends AppCompatActivity {
    
    private static final int SPLASH_DELAY = 1000; // 1秒延迟
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        // 延迟后检查登录状态
        new Handler(Looper.getMainLooper()).postDelayed(this::checkLoginStatus, SPLASH_DELAY);
    }
    
    /**
     * 检查登录状态
     */
    private void checkLoginStatus() {
        // 使用BaiduAuthService检查登录状态，确保一致性
        BaiduAuthService authService = BaiduAuthService.getInstance(this);
        boolean isLoggedIn = authService.isAuthenticated();
        
        Intent intent;
        if (isLoggedIn) {
            // 检查是否有保存的播放状态
            SharedPreferences prefs = getSharedPreferences("AudioPlayerPrefs", MODE_PRIVATE);
            boolean hasSavedState = prefs.contains("playlist");
            
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
        
        startActivity(intent);
        finish();
    }
}