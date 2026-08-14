package com.panmusic.carplayer.model

/**
 * 播放页一次性事件（ViewModel → Activity）
 */
sealed interface PlaybackUiEvent {
    data class PlayAudio(val url: String) : PlaybackUiEvent
    data class ShowError(val message: String) : PlaybackUiEvent
    data object Finish : PlaybackUiEvent
}
