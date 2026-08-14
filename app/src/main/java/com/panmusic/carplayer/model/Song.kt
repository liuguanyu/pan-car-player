package com.panmusic.carplayer.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 歌曲实体 — Room 数据库表
 */
@Entity(
    tableName = "songs",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlist_id"])]
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long = 0,

    @ColumnInfo(name = "fs_id")
    val fsId: Long = 0,

    @ColumnInfo(name = "server_filename")
    val serverFilename: String = "",

    val path: String = "",

    val size: Long = 0,

    @ColumnInfo(name = "server_mtime")
    val serverMtime: Long = 0,

    val category: Int = 0,

    @ColumnInfo(name = "is_dir")
    val isDir: Int = 0,

    @ColumnInfo(name = "md5")
    val md5: String = "",

    @ColumnInfo(name = "dlink")
    val dlink: String = ""
) {
    companion object {
        /**
         * 按文件夹路径 + 文件名排序，与 Java 版 sortSongs 对齐
         */
        fun sortSongs(songs: MutableList<Song>, ascending: Boolean = true) {
            songs.sortWith { s1, s2 ->
                val dir1 = getDirectoryPath(s1.path)
                val dir2 = getDirectoryPath(s2.path)
                val dirCmp = dir1.compareTo(dir2, ignoreCase = true)
                if (dirCmp != 0) {
                    if (ascending) dirCmp else -dirCmp
                } else {
                    val titleCmp = s1.serverFilename.compareTo(s2.serverFilename, ignoreCase = true)
                    if (ascending) titleCmp else -titleCmp
                }
            }
        }

        private fun getDirectoryPath(fullPath: String): String {
            val lastSlash = fullPath.lastIndexOf('/')
            return if (lastSlash > 0) fullPath.substring(0, lastSlash) else ""
        }
    }
}
