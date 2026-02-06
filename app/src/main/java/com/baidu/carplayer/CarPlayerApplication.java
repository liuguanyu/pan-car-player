package com.baidu.carplayer;

import android.app.Application;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.database.DatabaseManager;

/**
 * 车载音乐播放器应用的Application类
 */
public class CarPlayerApplication extends Application {
    
    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化应用级别的组件
        initializeComponents();
    }
    
    /**
     * 初始化应用组件
     */
    private void initializeComponents() {
        // 初始化认证服务
        BaiduAuthService.getInstance(this);
        
        // 初始化数据库
        DatabaseManager.getInstance(this);
    }
}