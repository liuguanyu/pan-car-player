package com.panmusic.carplayer.model

import com.google.gson.annotations.SerializedName

/**
 * 百度网盘 — Token 响应
 */
data class TokenResponse(
    @SerializedName("access_token")
    val accessToken: String = "",

    @SerializedName("refresh_token")
    val refreshToken: String = "",

    @SerializedName("expires_in")
    val expiresIn: Long = 0,

    @SerializedName("scope")
    val scope: String = ""
)
