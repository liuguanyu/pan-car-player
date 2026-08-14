package com.panmusic.carplayer.service

import com.panmusic.carplayer.model.MediaCommand
import com.panmusic.carplayer.repository.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放控制器 — 单点消费 MediaCommand
 *
 * 用户按键命令收敛到此处。音频焦点由 ExoPlayer 自动管理
 * （见 PlayerModule 中 setAudioAttributes(..., handleAudioFocus = true)）
 */
@Singleton
class PlaybackController @Inject constructor(
    private val mediaKeyDispatcher: MediaKeyDispatcher,
    private val playbackRepository: PlaybackRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // 消费用户命令
        scope.launch {
            mediaKeyDispatcher.commands.collectLatest { command ->
                when (command) {
                    MediaCommand.Play -> playbackRepository.play()
                    MediaCommand.Pause -> playbackRepository.pause()
                    MediaCommand.TogglePlayPause -> playbackRepository.togglePlayPause()
                    MediaCommand.Next -> playbackRepository.playNext()
                    MediaCommand.Previous -> playbackRepository.playPrevious()
                }
            }
        }
    }
}
