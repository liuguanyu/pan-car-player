package com.panmusic.carplayer.model

/**
 * 媒体控制命令 — 统一按键总线
 */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object TogglePlayPause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
}
