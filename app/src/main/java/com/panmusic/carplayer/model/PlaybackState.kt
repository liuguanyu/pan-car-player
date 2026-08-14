package com.panmusic.carplayer.model

/**
 * 播放状态机 — 编译期穷尽检查
 */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data class Playing(val position: Long, val duration: Long) : PlaybackState
    data class Paused(val position: Long, val duration: Long) : PlaybackState
    data class Error(val exception: Throwable) : PlaybackState
}
