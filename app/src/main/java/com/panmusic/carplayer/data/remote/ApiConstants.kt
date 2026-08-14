package com.panmusic.carplayer.data.remote

/**
 * 百度网盘 API 常量
 */
object ApiConstants {
    // OAuth
    const val OAUTH_BASE_URL = "https://openapi.baidu.com/"
    const val PAN_BASE_URL = "https://pan.baidu.com/"

    // 设备码授权
    const val DEVICE_CODE_PATH = "oauth/2.0/device/code"
    const val TOKEN_PATH = "oauth/2.0/token"

    // 网盘 API
    const val FILE_LIST_PATH = "rest/2.0/xpan/file"
    const val FILE_SEARCH_PATH = "rest/2.0/xpan/file"
    const val FILE_METAS_PATH = "rest/2.0/xpan/multimedia"
    const val DOWNLOAD_LINK_PATH = "rest/2.0/xpan/multimedia"

    // 响应码
    const val RESPONSE_SUCCESS = "success"
    const val RESPONSE_AUTHORIZED = "authorized"
}
