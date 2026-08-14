package com.panmusic.carplayer.model

/**
 * 音频焦点变化命令
 */
sealed interface FocusChange {
    data object Gained : FocusChange
    data object LostPermanent : FocusChange
    data object LostTransient : FocusChange
    data object LostTransientCanDuck : FocusChange
}
