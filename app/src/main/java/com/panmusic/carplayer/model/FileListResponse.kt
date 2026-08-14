package com.panmusic.carplayer.model

import com.google.gson.annotations.SerializedName

/**
 * 百度网盘 — 文件列表响应
 */
data class FileListResponse(
    @SerializedName("list")
    val list: List<FileItem> = emptyList(),

    @SerializedName("has_more")
    val hasMore: Int = 0,

    @SerializedName("cursor")
    val cursor: String = ""
)
