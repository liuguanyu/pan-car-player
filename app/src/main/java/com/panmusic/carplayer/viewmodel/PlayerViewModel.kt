package com.panmusic.carplayer.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panmusic.carplayer.model.FormatInfo
import com.panmusic.carplayer.model.PlayMode
import com.panmusic.carplayer.model.PlaybackState
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.repository.LyricsRepository
import com.panmusic.carplayer.repository.PlaybackRepository
import com.panmusic.carplayer.util.LrcParser
import com.panmusic.carplayer.visualizer.VisualizerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackRepository: PlaybackRepository,
    private val settingsRepository: SettingsRepository,
    private val lyricsRepository: LyricsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    val playlistSongs: StateFlow<List<Song>> = playbackRepository.playlistSongs
    val currentIndexFlow: StateFlow<Int> = playbackRepository.currentIndexFlow

    init {
        viewModelScope.launch {
            playbackRepository.playbackState.collectLatest { state ->
                _uiState.value = _uiState.value.copy(playbackState = state)
            }
        }
        viewModelScope.launch {
            playbackRepository.currentSong.collect { song ->
                Log.d(TAG, "currentSong emitted: song=${song?.serverFilename}, fsId=${song?.fsId}, path=${song?.path}")
                val prev = _uiState.value.currentSong
                _uiState.value = _uiState.value.copy(currentSong = song)
                // 切歌时自动加载歌词
                if (song != null && song.fsId != prev?.fsId) {
                    Log.d(TAG, "Calling loadLyrics for ${song.serverFilename}")
                    loadLyrics(song)
                }
            }
        }
        viewModelScope.launch {
            playbackRepository.progress.collectLatest { progress ->
                val dur = _uiState.value.duration
                _uiState.value = _uiState.value.copy(
                    progress = progress,
                    progressPercent = if (dur > 0) progress.toFloat() / dur else 0f
                )
            }
        }
        viewModelScope.launch {
            playbackRepository.formatInfo.collectLatest { format ->
                _uiState.value = _uiState.value.copy(formatInfo = format)
            }
        }
        viewModelScope.launch {
            settingsRepository.playMode.collectLatest { mode ->
                _uiState.value = _uiState.value.copy(playMode = mode)
            }
        }
        viewModelScope.launch {
            settingsRepository.preferredVisualizer.collectLatest { name ->
                _uiState.value = _uiState.value.copy(activeVisualizer = name)
            }
        }

        // 观察音频会话 ID（供可视化绑定）
        viewModelScope.launch {
            playbackRepository.audioSessionId.collectLatest { id ->
                if (id > 0) {
                    _uiState.value = _uiState.value.copy(audioSessionId = id)
                }
            }
        }
    }

    // ========== 播放控制 ==========

    fun play() = playbackRepository.play()
    fun pause() = playbackRepository.pause()
    fun togglePlayPause() = playbackRepository.togglePlayPause()
    fun playNext() = playbackRepository.playNext()
    fun playPrevious() = playbackRepository.playPrevious()
    fun seekTo(position: Long) = playbackRepository.seekTo(position)
    fun setVolume(volume: Float) = playbackRepository.setVolume(volume)
    fun getVolume(): Float = playbackRepository.getVolume()

    // ========== 播放模式 ==========

    fun cyclePlayMode() {
        val next = when (settingsRepository.playMode.value) {
            PlayMode.ORDER -> PlayMode.RANDOM
            PlayMode.RANDOM -> PlayMode.SINGLE
            PlayMode.SINGLE -> PlayMode.ORDER
        }
        settingsRepository.setPlayMode(next)
    }

    // ========== 播放列表 ==========

    fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        viewModelScope.launch {
            playbackRepository.setPlaylist(songs, startIndex)
        }
    }

    fun playAt(index: Int) {
        viewModelScope.launch {
            playbackRepository.playAt(index)
        }
    }

    // ========== 歌词 ==========

    private fun loadLyrics(song: Song) {
        _uiState.value = _uiState.value.copy(lyricsLoading = true, lrcInfo = null)
        viewModelScope.launch {
            lyricsRepository.loadLyrics(song)
                .onSuccess { info ->
                    _uiState.value = _uiState.value.copy(
                        lrcInfo = info,
                        lyricsLoading = false
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        lyricsLoading = false,
                        lrcInfo = null
                    )
                }
        }
    }

    /**
     * 根据当前播放进度更新歌词高亮行索引
     */
    fun updateLyricPosition(positionMs: Long) {
        val lines = _uiState.value.lrcInfo?.lines ?: return
        val index = LrcParser.findCurrentLine(lines, positionMs)
        _uiState.value = _uiState.value.copy(currentLyricIndex = index)
    }

    // ========== 可视化 ==========

    fun cycleVisualizer(visualizerNames: List<String>) {
        // 首位为默认唱片模式（对齐 Java 版），其后为各可视化效果
        val modes = listOf(VisualizerManager.MODE_DEFAULT) + visualizerNames
        val current = _uiState.value.activeVisualizer
        val idx = modes.indexOf(current)
        val next = modes.getOrElse((idx + 1) % modes.size) { modes.first() }
        settingsRepository.setPreferredVisualizer(next)
    }
}

data class PlayerUiState(
    val playbackState: PlaybackState = PlaybackState.Idle,
    val currentSong: Song? = null,
    val progress: Long = 0L,
    val progressPercent: Float = 0f,
    val formatInfo: FormatInfo = FormatInfo(),
    val playMode: PlayMode = PlayMode.ORDER,
    // 歌词
    val lrcInfo: LrcParser.LrcInfo? = null,
    val currentLyricIndex: Int = -1,
    val lyricsLoading: Boolean = false,
    // 可视化（默认为唱片模式）
    val activeVisualizer: String = VisualizerManager.MODE_DEFAULT,
    val audioSessionId: Int = 0
) {
    val isPlaying: Boolean get() = playbackState is PlaybackState.Playing

    val songTitle: String get() = currentSong?.serverFilename ?: ""

    val duration: Long get() = when (playbackState) {
        is PlaybackState.Playing -> (playbackState as PlaybackState.Playing).duration
        is PlaybackState.Paused -> (playbackState as PlaybackState.Paused).duration
        else -> 0L
    }

    val formattedProgress: String get() = formatTime(progress)
    val formattedDuration: String get() = formatTime(duration)

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
