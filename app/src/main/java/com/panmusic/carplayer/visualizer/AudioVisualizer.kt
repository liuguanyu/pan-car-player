package com.panmusic.carplayer.visualizer

import android.graphics.Canvas

/**
 * 音频可视化接口 — 所有可视化效果统一入口
 */
interface AudioVisualizer {
    /**
     * 更新 FFT 数据
     * @param fft FFT 数据数组
     * @param width 画布宽度
     * @param height 画布高度
     */
    fun update(fft: ByteArray, width: Int, height: Int)

    /**
     * 绘制当前帧
     */
    fun draw(canvas: Canvas)

    /**
     * 重置状态
     */
    fun reset()

    /**
     * 可视化名称
     */
    val name: String
}
