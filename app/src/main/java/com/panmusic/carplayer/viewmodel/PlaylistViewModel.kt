package com.panmusic.carplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panmusic.carplayer.model.Playlist
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.repository.FileRepository
import com.panmusic.carplayer.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PlaylistUiEvent>()
    val events: SharedFlow<PlaylistUiEvent> = _events.asSharedFlow()

    init {
        // 观察播放列表变化
        viewModelScope.launch {
            playlistRepository.getAllPlaylists().collectLatest { playlists ->
                _uiState.value = _uiState.value.copy(
                    playlists = playlists,
                    isLoading = false
                )
            }
        }
    }

    /**
     * 创建播放列表
     */
    fun createPlaylist(name: String) {
        viewModelScope.launch {
            try {
                val newId = playlistRepository.createPlaylist(name)
                _events.emit(PlaylistUiEvent.PlaylistCreated(newId))
            } catch (e: Exception) {
                _events.emit(PlaylistUiEvent.ShowError(e.message ?: "创建失败"))
            }
        }
    }

    /**
     * 重命名播放列表
     */
    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch {
            try {
                playlistRepository.updatePlaylist(playlist.copy(name = newName))
                _events.emit(PlaylistUiEvent.PlaylistRenamed)
            } catch (e: Exception) {
                _events.emit(PlaylistUiEvent.ShowError(e.message ?: "重命名失败"))
            }
        }
    }

    /**
     * 删除播放列表
     */
    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            try {
                playlistRepository.deletePlaylist(id)
                _events.emit(PlaylistUiEvent.PlaylistDeleted)
            } catch (e: Exception) {
                _events.emit(PlaylistUiEvent.ShowError(e.message ?: "删除失败"))
            }
        }
    }

    /**
     * 获取播放列表的歌曲
     */
    fun loadSongs(playlistId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSongs = true)
            try {
                val songs = playlistRepository.getSongs(playlistId)
                _uiState.value = _uiState.value.copy(
                    currentSongs = songs,
                    isLoadingSongs = false
                )
                _events.emit(PlaylistUiEvent.SongsLoaded(playlistId, songs))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingSongs = false)
                _events.emit(PlaylistUiEvent.ShowError(e.message ?: "加载歌曲失败"))
            }
        }
    }

    /**
     * 手动刷新 — 显示最小 400ms 加载动画，提供可见的用户反馈
     * 数据由 Room Flow 自动推送到 UI，无需额外查询
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            delay(400)
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    /**
     * 刷新单个播放列表 — 重新扫描百度网盘同步歌曲（参考 Java 版算法）
     */
    fun refreshPlaylist(playlistId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)
            try {
                val result = playlistRepository.refreshPlaylist(playlistId, fileRepository)
                result.fold(
                    onSuccess = { count ->
                        _events.emit(PlaylistUiEvent.ShowMessage("刷新完成，共 ${count} 首歌曲"))
                    },
                    onFailure = { e ->
                        _events.emit(PlaylistUiEvent.ShowError(e.message ?: "刷新失败"))
                    }
                )
            } catch (e: Exception) {
                _events.emit(PlaylistUiEvent.ShowError(e.message ?: "刷新失败"))
            } finally {
                _uiState.value = _uiState.value.copy(isRefreshing = false)
            }
        }
    }
}

data class PlaylistUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val playlists: List<Playlist> = emptyList(),
    val isLoadingSongs: Boolean = false,
    val currentSongs: List<Song> = emptyList()
)

sealed interface PlaylistUiEvent {
    data class PlaylistCreated(val playlistId: Long) : PlaylistUiEvent
    data object PlaylistRenamed : PlaylistUiEvent
    data object PlaylistDeleted : PlaylistUiEvent
    data class SongsLoaded(val playlistId: Long, val songs: List<Song>) : PlaylistUiEvent
    data class ShowError(val message: String) : PlaylistUiEvent
    data class ShowMessage(val message: String) : PlaylistUiEvent
}
