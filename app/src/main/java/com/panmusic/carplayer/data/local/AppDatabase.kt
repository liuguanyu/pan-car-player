package com.panmusic.carplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song

@Database(
    entities = [Playlist::class, Song::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
}
