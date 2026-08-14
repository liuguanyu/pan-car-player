package com.panmusic.carplayer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.panmusic.carplayer.model.FocusChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音频焦点管理器 — 从匿名回调到 StateFlow
 * 脱离 Service 生命周期，可独立单元测试
 */
class AudioFocusManager(
    private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _focusChanges = MutableStateFlow<FocusChange>(FocusChange.Gained)
    val focusChanges: StateFlow<FocusChange> = _focusChanges.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        _focusChanges.value = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.Gained
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LostPermanent
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LostTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LostTransientCanDuck
            else -> FocusChange.Gained
        }
    }

    /**
     * 请求音频焦点
     */
    fun request(): Boolean {
        return try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
                result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            granted
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 释放音频焦点
     */
    fun abandon() {
        try {
            focusRequest?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioManager.abandonAudioFocusRequest(it)
                }
            } ?: run {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        } catch (_: Exception) {
        }
        _focusChanges.value = FocusChange.Gained
    }
}
