package com.panmusic.carplayer.di

import android.content.Context
import androidx.room.Room
import com.panmusic.carplayer.data.local.AppDatabase
import com.panmusic.carplayer.data.local.PlaylistDao
import com.panmusic.carplayer.data.local.SongDao
import com.panmusic.carplayer.data.local.TransactionRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "pan_music_car.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePlaylistDao(db: AppDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideSongDao(db: AppDatabase): SongDao = db.songDao()

    @Provides
    @Singleton
    fun provideTransactionRunner(db: AppDatabase): TransactionRunner {
        return TransactionRunner { block ->
            db.runInTransaction {
                runBlocking { block() }
            }
        }
    }
}
