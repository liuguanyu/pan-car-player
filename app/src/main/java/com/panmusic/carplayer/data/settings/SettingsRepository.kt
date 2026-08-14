package com.panmusic.carplayer.data.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.panmusic.carplayer.model.PlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 设置持久化 Repository — 每个设置项一个 StateFlow
 */
@Singleton
class SettingsRepository @Inject constructor(
    @Named("settings_prefs") private val prefs: SharedPreferences
) {
    companion object {
        private const val KEY_PLAY_MODE = "play_mode"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_PREFERRED_VISUALIZER = "preferred_visualizer"
    }

    // —— 播放模式 ——
    private val _playMode = MutableStateFlow(readPlayMode())
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    fun setPlayMode(mode: PlayMode) {
        prefs.edit { putInt(KEY_PLAY_MODE, mode.value) }
        _playMode.value = mode
    }

    private fun readPlayMode(): PlayMode {
        val value = prefs.getInt(KEY_PLAY_MODE, PlayMode.ORDER.value)
        return PlayMode.fromValue(value)
    }

    // —— 登录状态 ——
    private val _isLoggedIn = MutableStateFlow(prefs.getBoolean(KEY_IS_LOGGED_IN, false))
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    fun setLoggedIn(loggedIn: Boolean) {
        prefs.edit { putBoolean(KEY_IS_LOGGED_IN, loggedIn) }
        _isLoggedIn.value = loggedIn
    }

    // —— 播放状态 ——
    private val _isPlaying = MutableStateFlow(prefs.getBoolean(KEY_IS_PLAYING, false))
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun setPlaying(playing: Boolean) {
        prefs.edit { putBoolean(KEY_IS_PLAYING, playing) }
        _isPlaying.value = playing
    }

    // —— 可视化偏好 ——
    private val _preferredVisualizer = MutableStateFlow(
        prefs.getString(KEY_PREFERRED_VISUALIZER, "default") ?: "default"
    )
    val preferredVisualizer: StateFlow<String> = _preferredVisualizer.asStateFlow()

    fun setPreferredVisualizer(name: String) {
        prefs.edit { putString(KEY_PREFERRED_VISUALIZER, name) }
        _preferredVisualizer.value = name
    }
}
