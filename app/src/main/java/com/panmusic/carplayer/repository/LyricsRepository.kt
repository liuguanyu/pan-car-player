package com.panmusic.carplayer.repository

import android.util.Log
import com.panmusic.carplayer.util.LrcParser
import com.panmusic.carplayer.data.remote.BaiduPanService
import com.panmusic.carplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 歌词加载 Repository — 单一职责：从百度网盘搜索/下载 LRC 并解析
 *
 * 流程：
 * 1. 从歌曲路径反推目录和文件名 → 搜索同目录的 .lrc 文件
 * 2. 获取 lrc 文件下载链接
 * 3. 下载纯文本内容
 * 4. 调用 LrcParser 解析
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val baiduPanService: BaiduPanService,
    private val authRepository: AuthRepository,
    private val okHttpClient: OkHttpClient
) {
    /**
     * 加载指定歌曲的 LRC 歌词
     * @return 解析后的歌词信息，或 null
     */
    suspend fun loadLyrics(song: Song): Result<LrcParser.LrcInfo> {
        if (song.path.isEmpty()) {
            Log.w(TAG, "歌曲路径为空")
            return Result.failure(IllegalArgumentException("歌曲路径为空"))
        }

        val lrcFileName = extractFileNameWithoutExtension(song.path) + ".lrc"
        val songDir = extractDirectory(song.path)
        Log.d(TAG, "加载歌词: fileName=$lrcFileName, dir=$songDir, songPath=${song.path}")

        // 1. 获取有效 token
        val token = authRepository.getValidAccessToken()
            ?: run {
                Log.e(TAG, "Token 已过期")
                return Result.failure(TokenExpiredException("Token 已过期"))
            }

        // 2. 搜索 lrc 文件
        val fsId = searchLrcFile(token, lrcFileName, songDir)
        if (fsId == null) {
            Log.w(TAG, "未找到歌词文件: $lrcFileName in $songDir")
            return Result.failure(NoLyricsException("未找到歌词文件: $lrcFileName"))
        }
        Log.d(TAG, "找到歌词文件 fsId=$fsId")

        // 3. 获取下载链接
        val dlink = getLrcDownloadLink(token, fsId)
        if (dlink == null) {
            Log.e(TAG, "获取歌词下载链接失败 fsId=$fsId")
            return Result.failure(IOException("获取歌词下载链接失败"))
        }

        // 4. 下载内容并解析
        val content = downloadText(dlink)
        if (content == null) {
            Log.e(TAG, "歌词下载失败")
            return Result.failure(IOException("歌词下载失败"))
        }
        Log.d(TAG, "歌词内容长度: ${content.length}")

        val lrcInfo = LrcParser.parse(content)
        Log.d(TAG, "解析完成: ${lrcInfo.lines.size} 行")
        return Result.success(lrcInfo)
    }

    private suspend fun searchLrcFile(
        token: String,
        fileName: String,
        dir: String
    ): Long? {
        val response = baiduPanService.search(
            accessToken = token,
            key = fileName,
            dir = dir
        )
        if (!response.isSuccessful || response.body() == null) return null

        val body = response.body()!!
        val errno = body.get("errno")?.asInt ?: -1
        if (errno != 0) return null

        val list = body.getAsJsonArray("list") ?: return null
        for (i in 0 until list.size()) {
            val item = list[i].asJsonObject
            val name = item.get("server_filename")?.asString ?: continue
            if (fileName.equals(name, ignoreCase = true)) {
                return item.get("fs_id")?.asLong
            }
        }
        return null
    }

    private suspend fun getLrcDownloadLink(token: String, fsId: Long): String? {
        val response = baiduPanService.getDownloadLink(
            accessToken = token,
            fsids = "[$fsId]"
        )
        if (!response.isSuccessful || response.body() == null) return null

        val list = response.body()!!.getAsJsonArray("list") ?: return null
        if (list.size() == 0) return null

        val rawDlink = list[0].asJsonObject.get("dlink")?.asString ?: return null
        // 拼上 access_token
        return "$rawDlink&access_token=$token"
    }

    private suspend fun downloadText(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "下载失败: HTTP ${response.code}, body=${response.body?.string()?.take(200)}")
                    null
                } else {
                    response.body?.string()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载异常: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "LyricsRepository"
        private fun extractFileNameWithoutExtension(path: String): String {
            val fileName = path.substringAfterLast('/')
            val lastDot = fileName.lastIndexOf('.')
            return if (lastDot > 0) fileName.substring(0, lastDot) else fileName
        }

        private fun extractDirectory(path: String): String {
            val lastSlash = path.lastIndexOf('/')
            return if (lastSlash > 0) path.substring(0, lastSlash) else "/"
        }
    }
}

/** 未找到歌词异常 */
class NoLyricsException(message: String) : Exception(message)
