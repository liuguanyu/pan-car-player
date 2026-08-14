package com.panmusic.carplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 车载网盘音乐播放器 Application
 */
@HiltAndroidApp
class PanMusicCarApplication : Application() {
    // Hilt 自动注入，无需手动初始化
}
