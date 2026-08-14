package com.panmusic.carplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.panmusic.carplayer.visualizer.VisualizerManager

/**
 * 可视化容器 View — 承载 FFT 可视化渲染
 */
class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var visualizerManager: VisualizerManager? = null
    private var isActive = false
    private var frameRateMs = 16L // 60fps

    fun attach(manager: VisualizerManager) {
        this.visualizerManager = manager
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (active) {
            postInvalidateDelayed(frameRateMs)
        }
    }

    fun setFrameRate(fps: Int) {
        frameRateMs = (1000L / fps).coerceIn(16, 200)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isActive = false
        visualizerManager?.release()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        visualizerManager?.draw(canvas, width, height)

        if (isActive) {
            postInvalidateDelayed(frameRateMs)
        }
    }
}
