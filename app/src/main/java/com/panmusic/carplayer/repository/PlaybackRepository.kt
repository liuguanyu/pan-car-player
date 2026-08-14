package com.panmusic.carplayer.repository

import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.panmusic.carplayer.data.local.SongDao
import com.panmusic.carplayer.data.remote.BaiduPanService
import com.panmusic.carplayer.data.settings.SettingsRepository
import com.panmusic.carplayer.model.FormatInfo
import com.panmusic.carplayer.model.PlayMode
import com.panmusic.carplayer.model.PlaybackState
import com.panmusic.carplayer.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 播放控制 Repository — ExoPlayer 播放控制 + 状态管理
 */
@Singleton
class PlaybackRepository @Inject constructor(
    private val exoPlayerProvider: Provider<ExoPlayer>,
    private val baiduPanService: BaiduPanService,
    private val authRepository: AuthRepository,
    private val songDao: SongDao,
    private val settingsRepository: SettingsRepository
) {
    // —— 播放状态 ——
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // —— 当前歌曲 ——
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    // —— 播放进度 (ms) ——
    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress.asStateFlow()

    // —— 音频格式信息 ——
    private val _formatInfo = MutableStateFlow<FormatInfo>(FormatInfo())
    val formatInfo: StateFlow<FormatInfo> = _formatInfo.asStateFlow()

    // —— 一次性事件 ——
    private val _events = MutableSharedFlow<PlaybackEvent>()
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    // —— 音频会话 ID（供可视化绑定） ——
    private val _audioSessionId = MutableStateFlow(0)
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    // —— 播放列表快照（供 UI 展示当前播单） ——
    private val _playlistSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistSongs: StateFlow<List<Song>> = _playlistSongs.asStateFlow()

    // —— 当前播放索引 ——
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndexFlow: StateFlow<Int> = _currentIndex.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // —— 内部状态 ——
    private var playlistSongIds: List<Long> = emptyList()
    private var currentIndex: Int = -1
    private var shuffleIndices: List<Int> = emptyList()
    private var shuffleIndex: Int = -1

    private val exoPlayer: ExoPlayer get() = exoPlayerProvider.get()

    init {
        setupPlayerListener()
        startProgressPolling()
    }

    /**
     * 定时轮询 ExoPlayer 进度，驱动 progress StateFlow
     */
    private fun startProgressPolling() {
        scope.launch {
            while (isActive) {
                val state = _playbackState.value
                if (state is PlaybackState.Playing || state is PlaybackState.Paused) {
                    _progress.value = exoPlayer.currentPosition
                }
                delay(500)
            }
        }
    }

    private fun setupPlayerListener() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> _playbackState.value = PlaybackState.Buffering
                    Player.STATE_READY -> {
                        _audioSessionId.value = exoPlayer.audioSessionId
                        val pos = exoPlayer.currentPosition
                        val dur = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
                        val playing = exoPlayer.playWhenReady
                        _playbackState.value = if (playing) {
                            PlaybackState.Playing(pos, dur)
                        } else {
                            PlaybackState.Paused(pos, dur)
                        }
                        updateFormatInfo()
                    }
                    Player.STATE_ENDED -> {
                        _playbackState.value = PlaybackState.Idle
                        handleTrackEnded()
                    }
                    Player.STATE_IDLE -> _playbackState.value = PlaybackState.Idle
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val pos = exoPlayer.currentPosition
                val dur = if (exoPlayer.duration > 0) exoPlayer.duration else 0L
                _playbackState.value = if (isPlaying) {
                    PlaybackState.Playing(pos, dur)
                } else {
                    PlaybackState.Paused(pos, dur)
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                // ExoPlayer 的 AudioTrack 是懒创建的（DefaultAudioSink.handleBuffer() 时），
                // STATE_READY 时 audioSessionId 仍为 0 (C.AUDIO_SESSION_ID_UNSET)。
                // 必须在此回调中捕获真正的 session ID，否则 Visualizer 无法绑定。
                if (audioSessionId != 0) {
                    _audioSessionId.value = audioSessionId
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _playbackState.value = PlaybackState.Error(error)
            }
        })
    }

    private fun updateFormatInfo() {
        val audioFormat = exoPlayer.audioFormat ?: return
        _formatInfo.value = FormatInfo(
            sampleRate = audioFormat.sampleRate,
            channelCount = audioFormat.channelCount,
            sampleMimeType = audioFormat.sampleMimeType ?: "",
            bitrate = audioFormat.bitrate.toLong(),
            codec = audioFormat.codecs ?: ""
        )
    }

    // ==================== 播放列表 ====================

    suspend fun setPlaylist(songs: List<Song>, startIndex: Int = 0) {
        playlistSongIds = songs.map { it.id }
        _playlistSongs.value = songs
        generateShuffleSequence()
        currentIndex = -1
        shuffleIndex = -1
        _currentIndex.value = -1
        playAt(startIndex)
    }

    suspend fun playAt(index: Int) {
        val effectiveIndex = if (index !in playlistSongIds.indices) {
            if (playlistSongIds.isEmpty()) return
            0
        } else index
        val songId = playlistSongIds[effectiveIndex]
        val song = songDao.getSongById(songId) ?: return
        val dlink = resolveDlink(song)
        if (dlink != null) {
            currentIndex = effectiveIndex
            _currentIndex.value = effectiveIndex
            shuffleIndex = shuffleIndices.indexOf(effectiveIndex)
            _currentSong.value = song
            val mediaItem = MediaItem.Builder()
                .setUri(dlink)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.serverFilename)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            savePlaybackState()
        }
    }

    /**
     * 获取歌曲的下载链接（带 Token 过期处理）
     */
    private suspend fun resolveDlink(song: Song): String? {
        val token = authRepository.getValidAccessToken()
            ?: run {
                // Token 过期，尝试刷新
                val refreshed = authRepository.handleApiAuthError()
                refreshed
            }
            ?: return null  // 刷新也失败，SessionManager 已 emit AuthExpired

        return try {
            val response = baiduPanService.getDownloadLink(
                accessToken = token,
                fsids = "[${song.fsId}]"
            )
            if (response.isSuccessful && response.body() != null) {
                val list = response.body()!!.getAsJsonArray("list")
                if (list != null && list.size() > 0) {
                    val rawDlink = list[0].asJsonObject.get("dlink")?.asString
                    rawDlink?.let { "$it&access_token=$token" }
                } else null
            } else {
                // API 返回认证错误 → 刷新后重试一次
                if (isAuthError(response.code())) {
                    val newToken = authRepository.handleApiAuthError() ?: return null
                    val retryResponse = baiduPanService.getDownloadLink(
                        accessToken = newToken,
                        fsids = "[${song.fsId}]"
                    )
                    if (retryResponse.isSuccessful && retryResponse.body() != null) {
                        val retryList = retryResponse.body()!!.getAsJsonArray("list")
                        if (retryList != null && retryList.size() > 0) {
                            val rawDlink = retryList[0].asJsonObject.get("dlink")?.asString
                            rawDlink?.let { "$it&access_token=$newToken" }
                        } else null
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isAuthError(httpCode: Int): Boolean = httpCode == 401 || httpCode == 403

    // ==================== 播放控制 ====================

    fun play() {
        exoPlayer.play()
        settingsRepository.setPlaying(true)
    }

    fun pause() {
        exoPlayer.pause()
        settingsRepository.setPlaying(false)
        savePlaybackState()
    }

    fun togglePlayPause() {
        if (exoPlayer.playWhenReady) pause() else play()
    }

    fun playNext() {
        when (settingsRepository.playMode.value) {
            PlayMode.ORDER -> scope.launch { playAt(currentIndex + 1) }
            PlayMode.RANDOM -> {
                val nextShuffle = shuffleIndex + 1
                val hasNext = nextShuffle < shuffleIndices.size
                val idx = if (hasNext) nextShuffle else 0
                if (!hasNext) {
                    generateShuffleSequence()
                }
                if (idx < shuffleIndices.size) {
                    shuffleIndex = idx
                    currentIndex = shuffleIndices[shuffleIndex]
                    scope.launch { playAt(currentIndex) }
                }
            }
            PlayMode.SINGLE -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
        }
    }

    fun playPrevious() {
        when (settingsRepository.playMode.value) {
            PlayMode.ORDER -> scope.launch { playAt(currentIndex - 1) }
            PlayMode.RANDOM -> {
                val prevShuffle = shuffleIndex - 1
                if (prevShuffle < 0) shuffleIndex = shuffleIndices.lastIndex
                else shuffleIndex = prevShuffle
                currentIndex = shuffleIndices[shuffleIndex]
                scope.launch { playAt(currentIndex) }
            }
            PlayMode.SINGLE -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
        }
    }

    fun seekTo(position: Long) {
        exoPlayer.seekTo(position)
        savePlaybackState()
    }

    fun setVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = exoPlayer.volume

    fun getCurrentPosition(): Long = exoPlayer.currentPosition
    fun getDuration(): Long = if (exoPlayer.duration > 0) exoPlayer.duration else 0L

    // ==================== 进度持久化 ====================

    private fun savePlaybackState() {
        // 保存当前播放列表 ID、位置、进度到 SharedPreferences
    }

    suspend fun restorePlaybackState(): Boolean = false

    // ==================== Fisher-Yates 洗牌 ====================

    private fun generateShuffleSequence() {
        if (playlistSongIds.isEmpty()) {
            shuffleIndices = emptyList()
            return
        }
        val n = playlistSongIds.size
        val indices = (0 until n).toMutableList()

        for (i in n - 1 downTo 1) {
            val j = (Math.random() * (i + 1)).toInt()
            val temp = indices[i]
            indices[i] = indices[j]
            indices[j] = temp
        }

        if (shuffleIndices.isNotEmpty() && indices.firstOrNull() == shuffleIndices.lastOrNull()) {
            val swapIdx = 1 + (Math.random() * (n - 1)).toInt()
            val idx = minOf(swapIdx, n - 1)
            val temp = indices[0]
            indices[0] = indices[idx]
            indices[idx] = temp
        }

        shuffleIndices = indices
    }

    private fun handleTrackEnded() {
        when (settingsRepository.playMode.value) {
            PlayMode.SINGLE -> {
                exoPlayer.seekTo(0)
                exoPlayer.play()
            }
            else -> playNext()
        }
    }

    fun release() {
        scope.cancel()
        exoPlayer.release()
    }
}

sealed interface PlaybackEvent {
    data class Error(val message: String) : PlaybackEvent
    data object TrackChanged : PlaybackEvent
}
