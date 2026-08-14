package com.panmusic.carplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panmusic.carplayer.model.FileItem
import com.panmusic.carplayer.model.Song
import com.panmusic.carplayer.repository.FileRepository
import com.panmusic.carplayer.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FileBrowserEvent>()
    val events: SharedFlow<FileBrowserEvent> = _events.asSharedFlow()

    fun loadDirectory(path: String = "/") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                currentPath = path,
                error = null
            )
            val result = fileRepository.getFileList(path)
            result.fold(
                onSuccess = { items ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        items = items,
                        parentPath = if (path == "/") null else path.substringBeforeLast("/").ifEmpty { "/" }
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
            )
        }
    }

    fun toggleSelection(item: FileItem) {
        val current = _uiState.value.selectedItems.toMutableList()
        val existing = current.find { it.fsId == item.fsId }
        if (existing != null) {
            current.remove(existing)
        } else {
            current.add(item)
        }
        _uiState.value = _uiState.value.copy(selectedItems = current)
    }

    fun scanAndAddToPlaylist(playlistId: Long, dir: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val result = fileRepository.scanFolder(dir)
            result.fold(
                onSuccess = { songs ->
                    playlistRepository.addSongs(playlistId, songs)
                    _uiState.value = _uiState.value.copy(isScanning = false)
                    _events.emit(FileBrowserEvent.ScanComplete(songs.size))
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(isScanning = false)
                    _events.emit(FileBrowserEvent.Error(e.message ?: "扫描失败"))
                }
            )
        }
    }

    fun addSelectedToPlaylist(playlistId: Long) {
        viewModelScope.launch {
            val selected = _uiState.value.selectedItems
            if (selected.isEmpty()) return@launch

            _uiState.value = _uiState.value.copy(isScanning = true)
            try {
                // 对每个选中的文件夹进行递归扫描
                val allSongs = mutableListOf<Song>()
                for (item in selected) {
                    if (item.isDir == 1) {
                        val result = fileRepository.scanFolder(item.path)
                        result.getOrNull()?.let { allSongs.addAll(it) }
                    } else if (isAudioFile(item.serverFilename)) {
                        allSongs.add(
                            Song(
                                fsId = item.fsId,
                                serverFilename = item.serverFilename,
                                path = item.path,
                                size = item.size,
                                serverMtime = item.serverMtime,
                                category = item.category,
                                isDir = item.isDir,
                                md5 = item.md5
                            )
                        )
                    }
                }
                playlistRepository.addSongs(playlistId, allSongs)
                _uiState.value = _uiState.value.copy(isScanning = false, selectedItems = emptyList())
                _events.emit(FileBrowserEvent.ScanComplete(allSongs.size))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isScanning = false)
                _events.emit(FileBrowserEvent.Error(e.message ?: "添加失败"))
            }
        }
    }

    private fun isAudioFile(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "ape")
    }
}

data class FileBrowserUiState(
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val currentPath: String = "/",
    val parentPath: String? = null,
    val items: List<FileItem> = emptyList(),
    val selectedItems: List<FileItem> = emptyList(),
    val error: String? = null
)

sealed interface FileBrowserEvent {
    data class ScanComplete(val count: Int) : FileBrowserEvent
    data class Error(val message: String) : FileBrowserEvent
}
