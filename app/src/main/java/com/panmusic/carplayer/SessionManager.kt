package com.panmusic.carplayer

import android.content.SharedPreferences
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.di.AuthPrefs
import com.panmusic.carplayer.model.AuthInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局会话管理器 — Token 生命周期 + 自动刷新 + 过期事件
 *
 * 职责：
 * 1. 提供有效的 Access Token（自动检测过期并刷新）
 * 2. 当 Token 不可恢复时，发射 AuthExpired 事件 → UI 层跳转登录
 * 3. Mutex 保护并发刷新（多个请求同时发现过期时只刷新一次）
 */
@Singleton
class SessionManager @Inject constructor(
    @AuthPrefs private val authPrefs: SharedPreferences,
    private val settingsRepository: SettingsRepository
) {
    /** 刷新互斥锁 — 同一时刻只有一个协程执行 refresh */
    private val refreshMutex = Mutex()

    /** 会话事件 — BaseCarActivity 监听此 Flow 响应登录/登出导航 */
    private val _sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents.asSharedFlow()

    private val _isLoggedIn = MutableStateFlow(checkLoggedInFast())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * 快速检查登录状态（不刷新，仅检查本地缓存）
     */
    fun checkLoggedInFast(): Boolean {
        val token = authPrefs.getString("access_token", null) ?: return false
        val expiresIn = authPrefs.getLong("expires_in", 0)
        val obtainedAt = authPrefs.getLong("token_obtained_at", 0)
        val elapsed = (System.currentTimeMillis() - obtainedAt) / 1000
        return elapsed < expiresIn
    }

    /**
     * 获取有效的 Access Token
     *
     * 1. Token 未过期 → 直接返回
     * 2. Token 已过期 → 尝试 refresh（Mutex 保护）
     *    - 刷新成功 → 返回新 Token
     *    - 刷新失败 → emit AuthExpired → 返回 null
     */
    fun getAccessToken(): String? {
        val token = authPrefs.getString("access_token", null) ?: return null
        val expiresIn = authPrefs.getLong("expires_in", 0)
        val obtainedAt = authPrefs.getLong("token_obtained_at", 0)
        val elapsed = (System.currentTimeMillis() - obtainedAt) / 1000

        // 提前 5 分钟判定过期，避免并发请求刚好在边界时出现多个 401
        if (elapsed < (expiresIn - 300)) {
            return token
        }
        // Token 已过期，返回 null 让调用方走 refresh 逻辑
        return null
    }

    /**
     * 尝试刷新 Access Token（Mutex 保护）
     *
     * @param refresher 执行刷新操作的 suspend 函数
     * @return 新的 AuthInfo，或 null（刷新失败）
     */
    suspend fun tryRefreshToken(refresher: suspend () -> Result<AuthInfo>): AuthInfo? {
        return refreshMutex.withLock {
            // 双重检查：进入锁之后再次确认是否仍然需要刷新
            // （可能在等待锁的过程中已经被其他协程刷新了）
            val currentToken = getAccessToken()
            if (currentToken != null) {
                return@withLock AuthInfo(
                    accessToken = currentToken,
                    refreshToken = authPrefs.getString("refresh_token", "") ?: "",
                    expiresIn = authPrefs.getLong("expires_in", 0)
                )
            }

            val result = refresher()
            result.fold(
                onSuccess = { authInfo ->
                    _isLoggedIn.value = true
                    authInfo
                },
                onFailure = {
                    // 刷新失败 → 清除认证状态
                    handleAuthExpiredLocked()
                    null
                }
            )
        }
    }

    /**
     * 保存 Token（供 AuthRepository 在登录/刷新成功后调用）
     */
    fun saveToken(accessToken: String, refreshToken: String, expiresIn: Long) {
        authPrefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("expires_in", expiresIn)
            .putLong("token_obtained_at", System.currentTimeMillis())
            .apply()
        _isLoggedIn.value = true
        settingsRepository.setLoggedIn(true)
    }

    /**
     * API 调用返回了认证错误（如 HTTP 401 或 errno=110/111）
     * 尝试刷新一次，失败则清除认证并发射事件
     *
     * @param refresher 刷新函数，通常来自 AuthRepository.refreshAccessToken()
     * @return 新的有效 Token，或 null
     */
    suspend fun onApiAuthError(refresher: suspend () -> Result<AuthInfo>): String? {
        val authInfo = tryRefreshToken(refresher)
        return authInfo?.accessToken
    }

    /**
     * 获取存储的 refresh_token（供 AuthRepository 调用刷新 API）
     */
    fun getRefreshToken(): String? = authPrefs.getString("refresh_token", null)

    /**
     * 登出（用户主动操作）
     */
    fun logout() {
        authPrefs.edit().clear().apply()
        _isLoggedIn.value = false
        settingsRepository.setLoggedIn(false)
        _sessionEvents.tryEmit(SessionEvent.LoggedOut)
    }

    /**
     * 处理认证永久过期（refresh 也失败了）
     * 必须在 refreshMutex.withLock 内调用
     */
    private fun handleAuthExpiredLocked() {
        authPrefs.edit().clear().apply()
        _isLoggedIn.value = false
        settingsRepository.setLoggedIn(false)
        _sessionEvents.tryEmit(SessionEvent.AuthExpired)
    }
}

/**
 * 会话事件
 */
sealed interface SessionEvent {
    /** Token 过期且刷新失败 → 需要重新扫码登录 */
    data object AuthExpired : SessionEvent
    /** 用户主动登出 */
    data object LoggedOut : SessionEvent
}
