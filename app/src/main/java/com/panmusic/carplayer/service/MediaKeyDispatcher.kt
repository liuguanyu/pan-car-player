package com.panmusic.carplayer.service

import android.view.KeyEvent
import com.panmusic.carplayer.model.MediaCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

/**
 * 媒体按键统一分发器 — 命令总线模式
 *
 * 多源发射命令（Activity 按键、MediaSession 回调、通知栏按钮）→ 单点消费
 *
 * 每个 Activity 只需一行接入：
 *   override fun dispatchKeyEvent(e: KeyEvent) =
 *       mediaKeyDispatcher.dispatch(e) || super.dispatchKeyEvent(e)
 */
class MediaKeyDispatcher @Inject constructor() {

    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()

    /**
     * 处理 KeyEvent — 在 Activity.dispatchKeyEvent 中调用
     * @return true 表示事件已处理
     */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> MediaCommand.TogglePlayPause
            KeyEvent.KEYCODE_MEDIA_PLAY -> MediaCommand.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaCommand.Pause
            KeyEvent.KEYCODE_MEDIA_NEXT -> MediaCommand.Next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaCommand.Previous
            else -> return false
        }
        _commands.tryEmit(command)
        return true
    }

    /**
     * 直接发射命令（供 MediaSession 回调、通知栏按钮使用）
     */
    fun emit(command: MediaCommand) {
        _commands.tryEmit(command)
    }
}
