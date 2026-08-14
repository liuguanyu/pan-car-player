package com.panmusic.carplayer.model

/**
 * 百度网盘授权信息
 */
data class AuthInfo(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresIn: Long = 0,
    val scope: String = "",
    val tokenObtainedAt: Long = System.currentTimeMillis()
) {
    /**
     * Token 是否已过期（提前 5 分钟判定）
     */
    val isExpired: Boolean
        get() {
            val elapsed = (System.currentTimeMillis() - tokenObtainedAt) / 1000
            return elapsed >= (expiresIn - 300)
        }
}
