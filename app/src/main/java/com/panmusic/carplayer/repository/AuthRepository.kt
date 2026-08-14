package com.panmusic.carplayer.repository

import com.panmusic.carplayer.BuildConfig
import com.panmusic.carplayer.SessionManager
import com.panmusic.carplayer.data.remote.BaiduOAuthService
import com.panmusic.carplayer.model.AuthInfo
import com.panmusic.carplayer.model.DeviceCodeResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百度网盘 OAuth 授权流程 Repository
 *
 * Token 生命周期由 SessionManager 统一管理：
 * - 登录成功 → SessionManager.saveToken()
 * - Token 过期 → SessionManager.tryRefreshToken(refresher)
 * - 刷新失败 → SessionManager 自动 emit AuthExpired
 */
@Singleton
class AuthRepository @Inject constructor(
    private val oauthService: BaiduOAuthService,
    private val sessionManager: SessionManager
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        if (sessionManager.isLoggedIn.value) {
            _authState.value = AuthState.LoggedIn
        }
    }

    // ==================== OAuth 流程 ====================

    /**
     * 获取设备码 → 生成二维码所需 URL
     */
    suspend fun requestDeviceCode(): Result<DeviceCodeResponse> {
        return try {
            val response = oauthService.getDeviceCode(
                clientId = BuildConfig.BAIDU_APP_KEY
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.deviceCode.isNotEmpty()) {
                    _authState.value = AuthState.WaitingForScan(
                        deviceCode = body.deviceCode,
                        userCode = body.userCode,
                        qrUrl = "${body.verificationUrl}?code=${body.userCode}"
                    )
                    Result.success(body)
                } else {
                    _authState.value = AuthState.Error("获取设备码失败")
                    Result.failure(Exception("设备码为空"))
                }
            } else {
                _authState.value = AuthState.Error("网络错误: ${response.code()}")
                Result.failure(Exception("请求设备码失败: ${response.code()}"))
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    /**
     * 轮询获取 Token（每 6 秒轮询一次）
     */
    suspend fun pollForToken(deviceCode: String, maxAttempts: Int = 100): Result<AuthInfo> {
        _authState.value = AuthState.Polling
        for (attempt in 1..maxAttempts) {
            try {
                val response = oauthService.getTokenByDeviceCode(
                    deviceCode = deviceCode,
                    clientId = BuildConfig.BAIDU_APP_KEY,
                    clientSecret = BuildConfig.BAIDU_SECRET_KEY
                )
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.accessToken.isNotEmpty()) {
                        sessionManager.saveToken(body.accessToken, body.refreshToken, body.expiresIn)
                        _authState.value = AuthState.LoggedIn
                        return Result.success(
                            AuthInfo(
                                accessToken = body.accessToken,
                                refreshToken = body.refreshToken,
                                expiresIn = body.expiresIn
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // 继续轮询
            }
            delay(6_000L)
        }
        _authState.value = AuthState.Error("授权超时，请重试")
        return Result.failure(Exception("授权超时"))
    }

    // ==================== Token 生命周期 ====================

    /**
     * 判断当前 Token 是否仍然有效
     */
    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn.value

    /**
     * 获取有效 Token（自动处理过期检测）
     */
    fun getValidAccessToken(): String? = sessionManager.getAccessToken()

    /**
     * 刷新 Token 的执行器 — 传给 SessionManager.tryRefreshToken() 使用
     *
     * SessionManager 负责控制并发（Mutex），此函数只负责执行实际的 API 调用
     */
    suspend fun executeTokenRefresh(): Result<AuthInfo> {
        val refreshToken = sessionManager.getRefreshToken()
            ?: return Result.failure(Exception("本地无 refresh_token，需要重新登录"))

        return try {
            val response = oauthService.refreshToken(
                refreshToken = refreshToken,
                clientId = BuildConfig.BAIDU_APP_KEY,
                clientSecret = BuildConfig.BAIDU_SECRET_KEY
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                sessionManager.saveToken(body.accessToken, body.refreshToken, body.expiresIn)
                Result.success(
                    AuthInfo(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken,
                        expiresIn = body.expiresIn
                    )
                )
            } else {
                // 刷新失败（如 refresh_token 也过期了）
                Result.failure(Exception("Refresh token 已失效: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * API 返回认证错误时的处理入口
     *
     * 调用方（FileRepository / PlaybackRepository）在收到 401 或 errno=110/111 时调用此方法
     * @return 新的 access_token，或 null（需要重新登录）
     */
    suspend fun handleApiAuthError(): String? {
        val authInfo = sessionManager.tryRefreshToken { executeTokenRefresh() }
        return authInfo?.accessToken
    }

    /**
     * 登出
     */
    fun logout() = sessionManager.logout()
}

/**
 * 授权状态机
 */
sealed interface AuthState {
    data object Initial : AuthState
    data class WaitingForScan(
        val deviceCode: String,
        val userCode: String,
        val qrUrl: String
    ) : AuthState
    data object Polling : AuthState
    data object LoggedIn : AuthState
    data class Error(val message: String) : AuthState
}
