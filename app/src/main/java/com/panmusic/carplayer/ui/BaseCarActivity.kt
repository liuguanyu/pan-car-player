package com.panmusic.carplayer.ui

import android.content.Intent
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.panmusic.carplayer.SessionEvent
import com.panmusic.carplayer.SessionManager
import com.panmusic.carplayer.service.MediaKeyDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 基础 Activity — 统一处理方向盘按键 + Token 过期跳转
 *
 * 所有 Activity 继承此类即可自动获得：
 * 1. 方向盘媒体按键支持（一行接入）
 * 2. Token 过期 → 自动跳转 LoginActivity
 */
abstract class BaseCarActivity : AppCompatActivity() {

    @Inject
    lateinit var mediaKeyDispatcher: MediaKeyDispatcher

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onStart() {
        super.onStart()
        observeSessionEvents()
    }

    /**
     * 监听会话事件 — Token 过期时跳转登录页
     */
    private fun observeSessionEvents() {
        lifecycleScope.launch {
            sessionManager.sessionEvents.collectLatest { event ->
                when (event) {
                    SessionEvent.AuthExpired -> {
                        // Token 过期且刷新失败 → 强制跳转登录
                        Toast.makeText(
                            this@BaseCarActivity,
                            "登录已过期，请重新扫码登录",
                            Toast.LENGTH_LONG
                        ).show()

                        navigateToLogin()
                    }
                    SessionEvent.LoggedOut -> {
                        // 用户主动登出
                        navigateToLogin()
                    }
                }
            }
        }
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    /**
     * 方向盘媒体按键入口 — 子类不需要再覆写此方法
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return mediaKeyDispatcher.dispatch(event) || super.dispatchKeyEvent(event)
    }
}
