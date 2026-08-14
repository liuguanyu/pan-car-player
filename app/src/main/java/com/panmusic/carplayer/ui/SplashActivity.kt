package com.panmusic.carplayer.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.panmusic.carplayer.SessionManager
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.migration.AuthMigration
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 启动页 — 检查登录状态和播放状态，决定导航目标
 *
 * 逻辑：
 *   已登录 + 上次正在播放 → PlayerActivity (EXTRA_RESUME_PLAYBACK)
 *   已登录 + 未播放       → MainActivity
 *   未登录               → LoginActivity
 */
@AndroidEntryPoint
class SplashActivity : BaseCarActivity() {

    @Inject lateinit var authMigration: AuthMigration
    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 执行升级迁移（明文 Token → 加密存储）
        scope.launch {
            authMigration.migrate()
        }

        // 延迟 1s 后检查登录状态（给迁移和初始化时间）
        handler.postDelayed({
            checkStatus()
        }, 1000)
    }

    private fun checkStatus() {
        if (sessionManager.isLoggedIn.value) {
            // 已登录，检查是否上次正在播放
            if (settingsRepository.isPlaying.value) {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("EXTRA_RESUME_PLAYBACK", true)
                )
            } else {
                startActivity(Intent(this, MainActivity::class.java))
            }
        } else {
            // 未登录
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
