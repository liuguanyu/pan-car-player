package com.panmusic.carplayer.visualizer

import android.graphics.Canvas
import android.media.audiofx.Visualizer
import android.util.Log
import javax.inject.Inject

/**
 * 可视化管道管理器 — FFT 数据采集 + 分发到当前活跃的可视化器
 */
class VisualizerManager @Inject constructor() {

    companion object {
        private const val TAG = "VisualizerManager"

        /** 默认唱片模式：只显示唱片 + 粒子 + 旋转文字，无可视化覆盖层（对齐 Java 版） */
        const val MODE_DEFAULT = "default"
    }

    private var audioSessionId: Int = 0
    private var androidVisualizer: Visualizer? = null
    private var isEnabled = false

    private val analyzer = AudioAnalyzer()
    private val visualizers = mutableListOf<AudioVisualizer>()
    private var activeVisualizer: AudioVisualizer? = null

    private var lastWidth = 0
    private var lastHeight = 0
    private var lastFft = ByteArray(0)

    init {
        // 注册所有可视化器
        visualizers.add(BarsVisualizer())
        visualizers.add(WaveVisualizer())
        visualizers.add(ParticlesVisualizer())
        visualizers.add(DancingSheepVisualizer())
        activeVisualizer = visualizers.first()
    }

    /**
     * 绑定音频会话 ID
     */
    fun attachToSession(sessionId: Int) {
        this.audioSessionId = sessionId
        try {
            androidVisualizer?.release()
            androidVisualizer = Visualizer(sessionId).apply {
                val captureSize = Visualizer.getCaptureSizeRange()[1]
                setCaptureSize(captureSize)
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray,
                            samplingRate: Int
                        ) {
                            lastFft = fft
                            activeVisualizer?.update(fft, lastWidth, lastHeight)
                        }

                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            // 不处理波形数据
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                enabled = true
            }
            isEnabled = true
            Log.d(TAG, "Visualizer attached to session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach Visualizer", e)
            isEnabled = false
        }
    }

    /**
     * 切换可视化效果
     */
    fun switchTo(name: String) {
        activeVisualizer?.reset()
        activeVisualizer = visualizers.find { it.name == name } ?: visualizers.first()
        // 用最新数据初始化新可视化器
        if (lastFft.isNotEmpty()) {
            activeVisualizer?.update(lastFft, lastWidth, lastHeight)
        }
        Log.d(TAG, "Switched to visualizer: $name")
    }

    /**
     * 获取所有可视化器名称
     */
    fun getVisualizerNames(): List<String> = visualizers.map { it.name }

    /**
     * 获取当前活跃可视化器名称
     */
    fun getActiveName(): String = activeVisualizer?.name ?: "bars"

    /**
     * 绘制当前帧
     */
    fun draw(canvas: Canvas, width: Int, height: Int) {
        lastWidth = width
        lastHeight = height

        // 每帧都调用 update，确保维度正确且数据最新：
        // - FFT 回调在 binder 线程触发，传入的 lastWidth/lastHeight 可能为 0（首帧）
        // - draw 在主线程，有正确的 width/height
        // - lastFft 为空时（尚未收到 FFT 或 isEnabled=false）走 fallback
        if (!isEnabled || lastFft.isEmpty()) {
            activeVisualizer?.update(ByteArray(0), width, height)
        } else {
            activeVisualizer?.update(lastFft, width, height)
        }
        activeVisualizer?.draw(canvas)
    }

    /**
     * 设置启用/停用
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        try {
            androidVisualizer?.enabled = enabled
        } catch (_: Exception) {
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        isEnabled = false
        try {
            androidVisualizer?.enabled = false
        } catch (_: Exception) {
        }
        try {
            androidVisualizer?.release()
        } catch (_: Exception) {
        }
        androidVisualizer = null
        visualizers.forEach { it.reset() }
    }
}
