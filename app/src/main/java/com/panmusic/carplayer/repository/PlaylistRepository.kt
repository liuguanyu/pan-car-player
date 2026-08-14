package com.panmusic.carplayer.repository

import com.panmusic.carplayer.data.local.PlaylistDao
import com.panmusic.carplayer.data.local.SongDao
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放列表 Repository
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao
) {
    /**
     * 获取所有播放列表（响应式）
     */
    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    /**
     * 获取播放列表详情
     */
    suspend fun getPlaylistById(id: Long): Playlist? = playlistDao.getPlaylistById(id)

    /**
     * 创建播放列表
     */
    suspend fun createPlaylist(name: String): Long {
        val playlist = Playlist(name = name)
        return playlistDao.insert(playlist)
    }

    /**
     * 更新播放列表
     */
    suspend fun updatePlaylist(playlist: Playlist) = playlistDao.update(playlist)

    /**
     * 删除播放列表（级联删除歌曲）
     */
    suspend fun deletePlaylist(id: Long) = playlistDao.deleteById(id)

    /**
     * 获取播放列表下的歌曲
     */
    suspend fun getSongs(playlistId: Long): List<Song> =
        songDao.getSongsByPlaylistId(playlistId)

    /**
     * 添加歌曲到播放列表
     */
    suspend fun addSongs(playlistId: Long, songs: List<Song>) {
        val songsWithPlaylist = songs.map { it.copy(playlistId = playlistId) }
        songDao.insertAll(songsWithPlaylist)
        playlistDao.updateSongCount(playlistId)
    }

    /**
     * 删除歌曲
     */
    suspend fun deleteSongs(songIds: List<Long>, playlistId: Long) {
        songDao.deleteByIds(songIds)
        playlistDao.updateSongCount(playlistId)
    }

    /**
     * 检查歌曲是否已存在于播放列表
     */
    suspend fun isSongExists(fsId: Long, playlistId: Long): Boolean =
        songDao.existsByFsId(fsId, playlistId)

    /**
     * 刷新播放列表 — 重新扫描百度网盘，同步歌曲变更
     *
     * 算法（参考 Java 版 PlaylistManager.refreshPlaylist）：
     * 1. 获取当前歌曲列表，提取所有父路径
     * 2. calculateSyncRoots: 去重合并，只保留最顶层目录
     * 3. 递归扫描每个顶层目录
     * 4. 清空旧数据，插入新歌曲
     */
    suspend fun refreshPlaylist(
        playlistId: Long,
        fileRepository: FileRepository
    ): Result<Int> {
        // 1. 获取当前歌曲
        val currentSongs = songDao.getSongsByPlaylistId(playlistId)
        if (currentSongs.isEmpty()) {
            return Result.failure(Exception("列表为空，无法刷新"))
        }

        // 2. 计算扫描根路径集合
        val scanRoots = calculateSyncRoots(currentSongs)

        // 3. 扫描所有根路径
        val cloudSongs = mutableListOf<Song>()
        for (root in scanRoots) {
            val result = fileRepository.scanFolder(root, scanSubDirs = true)
            result.getOrNull()?.let { cloudSongs.addAll(it) }
        }

        // 4. 重建列表
        songDao.deleteByPlaylistId(playlistId)
        if (cloudSongs.isNotEmpty()) {
            songDao.insertAll(cloudSongs.map { it.copy(playlistId = playlistId, id = 0) })
        }
        playlistDao.updateSongCount(playlistId)

        return Result.success(cloudSongs.size)
    }

    /**
     * 核心算法：从歌曲路径推最小扫描根集合
     *
     * 策略：
     * - 提取所有歌曲的父文件夹路径
     * - 如果路径 A 是路径 B 的父级，则只保留 A
     * - 例如：["/music/rock", "/music"] → 只保留 ["/music"]
     */
    private fun calculateSyncRoots(songs: List<Song>): Set<String> {
        // 1. 收集所有唯一的父路径
        val parentPaths = songs.map { getParentPath(it.path) }
            .filterNotNull()
            .toSet()

        // 2. 按长度排序（短的在前面，父目录更短）
        val sortedPaths = parentPaths.sorted()

        // 3. 过滤被包含的子路径
        val roots = mutableSetOf<String>()
        for (path in sortedPaths) {
            val isChild = roots.any { root ->
                path.startsWith(root) &&
                    (path.length == root.length || path[root.length] == '/')
            }
            if (!isChild) {
                roots.add(path)
            }
        }

        return roots
    }

    private fun getParentPath(path: String): String? {
        if (path.isEmpty() || path == "/") return null
        val lastSlash = path.lastIndexOf('/')
        return when {
            lastSlash == 0 -> "/"
            lastSlash > 0 -> path.substring(0, lastSlash)
            else -> null
        }
    }

    /**
     * 删除指定播放列表的所有歌曲
     */
    suspend fun clearSongs(playlistId: Long) {
        songDao.deleteByPlaylistId(playlistId)
        playlistDao.updateSongCount(playlistId)
    }
}
