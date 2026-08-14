package com.panmusic.carplayer.di

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    private const val TAG = "AuthModule"
    private const val AUTH_PREFS_NAME = "baidu_auth"
    private const val AUTH_FALLBACK_NAME = "baidu_auth_plain_fallback"

    @Provides
    @Singleton
    @AuthPrefs
    fun provideAuthEncryptedSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                AUTH_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences 失败，降级明文", e)
            context.getSharedPreferences(AUTH_FALLBACK_NAME, Context.MODE_PRIVATE)
        }
    }
}
