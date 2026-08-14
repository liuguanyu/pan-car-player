package com.panmusic.carplayer.repository

import com.panmusic.carplayer.data.remote.BaiduPanService
import com.panmusic.carplayer.model.FileItem
import com.panmusic.carplayer.model.Song
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 文件浏览 Repository — 百度网盘文件操作
 *
 * Token 过期处理：每次 API 调用后检查是否需要刷新/重新登录
 */
@Singleton
class FileRepository @Inject constructor(
    private val baiduPanService: BaiduPanService,
    private val authRepository: AuthRepository
) {
    /**
     * 获取有效 Token — 如果过期则自动刷新
     */
    private suspend fun requireToken(): String? {
        val token = authRepository.getValidAccessToken()
        if (token != null) return token

        // Token 已过期，尝试刷新
        val refreshed = authRepository.handleApiAuthError()
        if (refreshed != null) return refreshed

        // 刷新也失败了 → SessionManager 已 emit AuthExpired
        return null
    }

    /**
     * 获取目录文件列表
     */
    suspend fun getFileList(dir: String = "/"): Result<List<FileItem>> {
        val token = requireToken() ?: return Result.failure(
            TokenExpiredException("Token 已过期，请重新登录")
        )

        return try {
            val response = baiduPanService.getFileList(
                accessToken = token,
                dir = dir
            )
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.list)
            } else if (isAuthError(response.code())) {
                retryAfterRefresh {
                    baiduPanService.getFileList(
                        accessToken = requireToken()!!,
                        dir = dir
                    )
                }?.let {
                    if (it.isSuccessful && it.body() != null) Result.success(it.body()!!.list)
                    else Result.failure(Exception("获取文件列表失败"))
                } ?: Result.failure(TokenExpiredException("Token 已过期，请重新登录"))
            } else {
                Result.failure(Exception("获取文件列表失败: ${response.code()}"))
            }
        } catch (e: TokenExpiredException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 递归扫描文件夹 — 协程结构化并发替代 pendingScans 计数器
     */
    suspend fun scanFolder(dir: String, scanSubDirs: Boolean = true): Result<List<Song>> {
        val token = requireToken() ?: return Result.failure(
            TokenExpiredException("Token 已过期，请重新登录")
        )

        return try {
            val songs = mutableListOf<Song>()
            collectAudioFiles(token, dir, songs, scanSubDirs)
            Result.success(songs)
        } catch (e: TokenExpiredException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun collectAudioFiles(
        token: String,
        dir: String,
        songs: MutableList<Song>,
        scanSubDirs: Boolean
    ) {
        val response = baiduPanService.getFileList(
            accessToken = token,
            dir = dir
        )
        if (!response.isSuccessful || response.body() == null) {
            if (isAuthError(response.code())) throw TokenExpiredException("Token 过期")
            return
        }

        val items = response.body()!!.list
        val files = items.filter { it.isDir == 0 }
        val folders = items.filter { it.isDir == 1 }

        songs.addAll(
            files.filter { isAudioFile(it.serverFilename) }
                .map { toSong(it) }
        )

        if (scanSubDirs && folders.isNotEmpty()) {
            folders.forEach { folder ->
                val currentToken = authRepository.getValidAccessToken()
                    ?: throw TokenExpiredException("Token 过期")
                collectAudioFiles(currentToken, folder.path, songs, true)
            }
        }
    }

    /**
     * API 返回认证错误（401 或 errno=110/111）时，刷新 Token 后重试一次
     */
    private suspend fun <T> retryAfterRefresh(block: suspend () -> T): T? {
        val newToken = authRepository.handleApiAuthError() ?: return null
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }

    private fun isAuthError(httpCode: Int): Boolean {
        return httpCode == 401 || httpCode == 403
    }

    private fun isAudioFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "ape")
    }

    private fun toSong(item: FileItem): Song {
        return Song(
            fsId = item.fsId,
            serverFilename = item.serverFilename,
            path = item.path,
            size = item.size,
            serverMtime = item.serverMtime,
            category = item.category,
            isDir = item.isDir,
            md5 = item.md5
        )
    }
}

/**
 * Token 过期异常 — 调用方可根据此异常类型判断是否需要跳转登录
 */
class TokenExpiredException(message: String) : Exception(message)
