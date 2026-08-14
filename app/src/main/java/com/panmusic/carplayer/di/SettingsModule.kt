package com.panmusic.carplayer.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    @Named("settings_prefs")
    fun provideSettingsSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("pan_music_settings", Context.MODE_PRIVATE)
    }
}
