package com.panmusic.carplayer.di

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.panmusic.carplayer.service.AudioFocusManager
import com.panmusic.carplayer.service.MediaKeyDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(android.os.PowerManager.PARTIAL_WAKE_LOCK)
            }
    }

    @Provides
    @Singleton
    fun provideAudioFocusManager(@ApplicationContext context: Context): AudioFocusManager {
        return AudioFocusManager(context)
    }

    @Provides
    @Singleton
    fun provideMediaKeyDispatcher(): MediaKeyDispatcher {
        return MediaKeyDispatcher()
    }
}
