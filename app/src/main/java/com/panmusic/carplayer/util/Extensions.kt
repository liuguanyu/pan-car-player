package com.panmusic.carplayer.util

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Canvas 扩展 — 消除 save/restore 配对模板
 */
inline fun Canvas.withSave(block: Canvas.() -> Unit) {
    save()
    try {
        block()
    } finally {
        restore()
    }
}

/**
 * Paint 扩展 — 链式配置
 */
inline fun Paint.apply(block: Paint.() -> Unit): Paint {
    this.block()
    return this
}

/**
 * 时间格式化 (ms → mm:ss)
 */
fun Long.toMmSs(): String {
    val totalSeconds = this / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * 文件大小格式化
 */
fun Long.toFileSizeString(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> "%.1f KB".format(this / 1024.0)
        this < 1024 * 1024 * 1024 -> "%.1f MB".format(this / (1024.0 * 1024))
        else -> "%.2f GB".format(this / (1024.0 * 1024 * 1024))
    }
}

/**
 * 限制值在 [min, max] 范围内
 */
fun Float.clamp(min: Float, max: Float): Float = coerceIn(min, max)

fun Int.clamp(min: Int, max: Int): Int = coerceIn(min, max)
