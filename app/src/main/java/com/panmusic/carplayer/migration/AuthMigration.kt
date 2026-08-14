package com.panmusic.carplayer.migration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.panmusic.carplayer.di.AuthPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性明文 → 加密 Token 迁移
 */
@Singleton
class AuthMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    @AuthPrefs private val encryptedPrefs: SharedPreferences
) {
    companion object {
        private const val TAG = "AuthMigration"
        private const val OLD_PREFS_NAME = "pan_music_auth"
        private const val MIGRATION_DONE_KEY = "auth_migration_done"
    }

    suspend fun migrate() {
        try {
            // 检查是否已迁移
            if (encryptedPrefs.getBoolean(MIGRATION_DONE_KEY, false)) {
                return
            }

            // 读取旧明文 SharedPreferences
            val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
            val oldAccessToken = oldPrefs.getString("access_token", null)
            val oldRefreshToken = oldPrefs.getString("refresh_token", null)

            if (oldAccessToken != null && oldRefreshToken != null) {
                // 写入加密存储
                encryptedPrefs.edit()
                    .putString("access_token", oldAccessToken)
                    .putString("refresh_token", oldRefreshToken)
                    .putLong("expires_in", oldPrefs.getLong("expires_in", 0))
                    .putLong("token_obtained_at", oldPrefs.getLong("token_obtained_at", 0))
                    .apply()

                // 删除旧明文文件
                oldPrefs.edit().clear().apply()
                Log.i(TAG, "Token 明文→加密迁移完成")
            }

            // 标记已迁移
            encryptedPrefs.edit().putBoolean(MIGRATION_DONE_KEY, true).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Token 迁移失败", e)
        }
    }
}
