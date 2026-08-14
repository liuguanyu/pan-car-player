package com.panmusic.carplayer.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * 波形图可视化 — FFT 波形曲线，青色描边
 */
class WaveVisualizer : AudioVisualizer {

    override val name = "wave"

    private var magnitudes = FloatArray(0)
    private var canvasWidth = 0
    private var canvasHeight = 0

    private val wavePaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#FF00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()

    override fun update(fft: ByteArray, width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height

        val raw = AudioAnalyzer.fftToMagnitudes(fft, 128)
        if (raw.isEmpty()) {
            // 无音频数据：生成默认波形（正弦波）
            magnitudes = FloatArray(64) { i ->
                0.3f + 0.2f * kotlin.math.sin(i * 0.3f).toFloat()
            }
            return
        }

        val next = FloatArray(raw.size)
        for (i in raw.indices) {
            val target = kotlin.math.sqrt(raw[i]).coerceIn(0f, 1f)
            val previous = magnitudes.getOrNull(i) ?: 0f
            val factor = if (target > previous) 0.50f else 0.18f
            next[i] = previous + (target - previous) * factor
        }
        magnitudes = next
    }

    override fun draw(canvas: Canvas) {
        if (magnitudes.isEmpty() || canvasWidth == 0 || canvasHeight == 0) return

        wavePath.reset()
        val stepX = canvasWidth.toFloat() / magnitudes.size

        val centerY = canvasHeight / 2f
        val amplitude = canvasHeight * 0.4f

        wavePath.moveTo(0f, centerY)
        for (i in magnitudes.indices) {
            val x = i * stepX
            val y = centerY - magnitudes[i] * amplitude + (0.5f - magnitudes[i]) * amplitude * 0.5f
            wavePath.lineTo(x, y)
        }

        // 镜像下半部分
        for (i in magnitudes.indices.reversed()) {
            val x = i * stepX
            val y = centerY + magnitudes[i] * amplitude - (0.5f - magnitudes[i]) * amplitude * 0.5f
            wavePath.lineTo(x, y)
        }
        wavePath.close()

        canvas.drawPath(wavePath, wavePaint)
    }

    override fun reset() {
        magnitudes = FloatArray(0)
    }
}
