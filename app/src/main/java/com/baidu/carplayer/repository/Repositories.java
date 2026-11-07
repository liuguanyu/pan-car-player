package com.baidu.carplayer.repository;

import android.content.Context;

import com.baidu.carplayer.auth.BaiduAuthService;
import com.baidu.carplayer.network.BaiduPanService;
import com.baidu.carplayer.network.RetrofitClient;

/** 页面获取仓库的唯一入口；令牌总是从认证服务实时读取。 */
public final class Repositories {
    private Repositories() {}

    public static CloudFileRepository cloudFiles(Context context) {
        BaiduAuthService auth = BaiduAuthService.getInstance(context);
        return new CloudFileRepository(RetrofitClient.getInstance().create(BaiduPanService.class),
                auth::getAccessToken);
    }
}
