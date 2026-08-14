package com.panmusic.carplayer.model

import com.google.gson.annotations.SerializedName

/**
 * 百度网盘 — 下载链接响应
 */
data class DownloadLinkResponse(
    @SerializedName("dlink")
    val dlink: String = "",

    @SerializedName("fs_id")
    val fsId: Long = 0,

    @SerializedName("md5")
    val md5: String = "",

    @SerializedName("errno")
    val errno: Int = 0
)
