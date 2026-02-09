package com.baidu.carplayer;

import android.content.Context;
import android.util.Log;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.database.DatabaseManager;

/**
 * 车载音乐播放器应用的Application类
 */
public class CarPlayerApplication extends MultiDexApplication {
    
    private static final String TAG = "CarPlayerApp";
    
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            MultiDex.install(this);
            Log.d(TAG, "MultiDex installed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error installing MultiDex", e);
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application onCreate started");
        
        // 初始化应用级别的组件
        try {
            initializeComponents();
            Log.d(TAG, "Components initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing components", e);
            // 不抛出异常，让应用继续运行
            // 稍后在需要时再进行懒加载初始化
        }
    }
    
    /**
     * 初始化应用组件
     */
    private void initializeComponents() {
        try {
            Log.d(TAG, "Initializing BaiduAuthService");
            BaiduAuthService.getInstance(this);
            Log.d(TAG, "BaiduAuthService initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing BaiduAuthService", e);
        }
        
        try {
            Log.d(TAG, "Initializing DatabaseManager");
            DatabaseManager.getInstance(this);
            Log.d(TAG, "DatabaseManager initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing DatabaseManager", e);
        }
    }
}