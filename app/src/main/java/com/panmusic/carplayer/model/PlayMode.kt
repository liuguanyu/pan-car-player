package com.panmusic.carplayer.model

/**
 * 播放模式
 */
enum class PlayMode(val value: Int) {
    ORDER(0),
    RANDOM(1),
    SINGLE(2);

    companion object {
        fun fromValue(value: Int): PlayMode =
            entries.firstOrNull { it.value == value } ?: ORDER
    }
}
