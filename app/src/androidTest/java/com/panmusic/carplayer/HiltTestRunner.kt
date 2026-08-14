package com.panmusic.carplayer

import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * 自定义 Hilt Test Runner — 用于 UI 集成测试
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: android.content.Context?): android.app.Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
