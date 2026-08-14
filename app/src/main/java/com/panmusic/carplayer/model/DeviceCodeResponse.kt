package com.panmusic.carplayer.model

import com.google.gson.annotations.SerializedName

/**
 * 百度网盘 — 设备码响应
 */
data class DeviceCodeResponse(
    @SerializedName("device_code")
    val deviceCode: String = "",

    @SerializedName("user_code")
    val userCode: String = "",

    @SerializedName("verification_url")
    val verificationUrl: String = "",

    @SerializedName("qrcode_url")
    val qrcodeUrl: String = "",

    @SerializedName("expires_in")
    val expiresIn: Int = 0,

    @SerializedName("interval")
    val interval: Int = 5
)
