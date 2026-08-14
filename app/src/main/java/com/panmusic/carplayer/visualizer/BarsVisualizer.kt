package com.panmusic.carplayer.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * 频谱柱状图可视化 — FFT 频率柱，渐变配色（绿→青→蓝）
 */
class BarsVisualizer : AudioVisualizer {

    override val name = "bars"

    private var magnitudes = FloatArray(0)
    private var barWidth = 0f
    private var canvasWidth = 0
    private var canvasHeight = 0

    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // 预创建 Shader，每帧只更新颜色位置，不 new
    private val gradientColors = intArrayOf(
        Color.parseColor("#FF00FF00"),  // 绿
        Color.parseColor("#FF00E5FF"),  // 青
        Color.parseColor("#FF2979FF")   // 蓝
    )
    private var cachedGradient: LinearGradient? = null
    private var cachedGradientWidth = -1

    override fun update(fft: ByteArray, width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height

        val raw = AudioAnalyzer.fftToMagnitudes(fft, 64)
        if (raw.isEmpty()) {
            // 无音频数据：生成默认柱形（中间高两边低）
            magnitudes = FloatArray(32) { i ->
                val center = 16f
                val dist = kotlin.math.abs(i - center) / center
                0.2f + 0.6f * (1f - dist)
            }
        } else {
            val next = FloatArray(raw.size)
            for (i in raw.indices) {
                // 感知压缩：弱信号抬升、强信号不过早满格
                val target = kotlin.math.sqrt(raw[i]).coerceIn(0f, 1f)
                val previous = magnitudes.getOrNull(i) ?: 0f
                val factor = if (target > previous) 0.55f else 0.22f // 快速上升、缓慢回落
                next[i] = previous + (target - previous) * factor
            }
            magnitudes = next
        }

        val barCount = magnitudes.size
        val gap = 2f
        barWidth = if (barCount > 0) (width - gap * (barCount + 1)) / barCount else 0f
    }

    override fun draw(canvas: Canvas) {
        if (magnitudes.isEmpty()) return

        val barCount = magnitudes.size
        val gap = 2f

        // 复用 Shader，宽度变化时才重建
        if (cachedGradientWidth != canvasWidth) {
            cachedGradient = LinearGradient(
                0f, 0f, canvasWidth.toFloat(), 0f,
                gradientColors,
                null,
                Shader.TileMode.CLAMP
            )
            cachedGradientWidth = canvasWidth
        }
        barPaint.shader = cachedGradient

        // 全宽频谱柱（唱片只在 default 模式显示，bars 为纯柱形图，无需避让中央）
        for (i in 0 until barCount) {
            val barHeight = magnitudes[i] * canvasHeight * 0.7f
            val x = gap + i * (barWidth + gap)
            val y = canvasHeight - barHeight
            canvas.drawRect(x, y, x + barWidth, canvasHeight.toFloat(), barPaint)
        }

        barPaint.shader = null
    }

    override fun reset() {
        magnitudes = FloatArray(0)
    }
}
