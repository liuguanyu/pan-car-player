package com.panmusic.carplayer.data.remote

import com.panmusic.carplayer.model.DownloadLinkResponse
import com.panmusic.carplayer.model.FileListResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 百度网盘 API 接口定义
 */
interface BaiduPanService {

    // ========== 文件操作 ==========

    @GET(ApiConstants.FILE_LIST_PATH)
    suspend fun getFileList(
        @Query("method") method: String = "list",
        @Query("access_token") accessToken: String,
        @Query("dir") dir: String = "/",
        @Query("order") order: String = "time",
        @Query("desc") desc: Int = 1,
        @Query("start") start: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("web") web: Int = 1
    ): Response<FileListResponse>

    @GET(ApiConstants.FILE_METAS_PATH)
    suspend fun getFileMetas(
        @Query("method") method: String = "filemetas",
        @Query("access_token") accessToken: String,
        @Query("fsids") fsids: String,
        @Query("dlink") dlink: Int = 1
    ): Response<com.google.gson.JsonObject>

    // ========== 下载链接 ==========

    @GET(ApiConstants.DOWNLOAD_LINK_PATH)
    suspend fun getDownloadLink(
        @Query("method") method: String = "filemetas",
        @Query("access_token") accessToken: String,
        @Query("fsids") fsids: String,
        @Query("dlink") dlink: Int = 1
    ): Response<com.google.gson.JsonObject>

    // ========== 搜索 ==========

    @GET(ApiConstants.FILE_SEARCH_PATH)
    suspend fun search(
        @Query("method") method: String = "search",
        @Query("access_token") accessToken: String,
        @Query("key") key: String,
        @Query("dir") dir: String
    ): Response<com.google.gson.JsonObject>
}
