package com.panmusic.carplayer.model

import com.google.gson.annotations.SerializedName

/**
 * 百度网盘文件项 — 网络响应中解析的文件/文件夹信息
 */
data class FileItem(
    @SerializedName("fs_id")
    val fsId: Long = 0,

    val path: String = "",

    @SerializedName("server_filename")
    val serverFilename: String = "",

    val size: Long = 0,

    @SerializedName("server_mtime")
    val serverMtime: Long = 0,

    val category: Int = 0,

    @SerializedName("isdir")
    val isDir: Int = 0,

    val md5: String = ""
)
