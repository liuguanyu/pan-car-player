package com.panmusic.carplayer.visualizer

import kotlin.math.sqrt

/**
 * 音频分析器 — 从 FFT 数据中提取音频特征
 *
 * 输入：Visualizer.onFftDataCapture 返回的 ByteArray (128/256 频率分量)
 * 输出：标准化后的分析结果
 */
class AudioAnalyzer {

    /**
     * 音频分析结果
     */
    data class AudioAnalysis(
        val bass: Float = 0f,           // 低频强度 0..1
        val mid: Float = 0f,            // 中频强度 0..1
        val treble: Float = 0f,         // 高频强度 0..1
        val volume: Float = 0f,         // 总音量 0..1
        val isPeak: Boolean = false,    // 是否节拍点
        val rawSpectrum: List<Float> = emptyList()  // 原始频谱数据
    )

    companion object {
        // FFT 大小通常是 128 或 256
        private const val FFT_SIZE = 128
        // 低频范围 (0-10%)
        private const val BASS_END = 6
        // 中频范围 (10-50%)
        private const val MID_END = 32
        // 高频范围 (50-100%)
        private const val TREBLE_START = 32
        // 节拍检测阈值
        private const val PEAK_THRESHOLD = 0.65f
        // 节拍冷却帧数
        private const val PEAK_COOLDOWN = 8

        /**
         * 将 Android Visualizer FFT 原始字节转换为 0..1 的频谱幅值。
         *
         * 关键点：Visualizer FFT 是 signed byte，不是 unsigned byte。
         * 之前使用 `and 0xFF` 会把负数（例如 -20）变成 236，导致大量频段被错误放大到满格，
         * 表现为 bars 杂乱、几乎全高、不像真实音频频谱。
         *
         * Android FFT 格式：
         *   fft[0] = DC real
         *   fft[1] = Nyquist real
         *   fft[2*k] = real(k), fft[2*k+1] = imag(k), k=1..N/2-1
         */
        fun fftToMagnitudes(fft: ByteArray, maxBins: Int = FFT_SIZE): FloatArray {
            if (fft.size < 2 || maxBins <= 0) return FloatArray(0)

            val binCount = (fft.size / 2).coerceAtMost(maxBins)
            val magnitudes = FloatArray(binCount)

            // DC 分量：通常不适合直接画得太高，保留但使用 signed 绝对值
            magnitudes[0] = (kotlin.math.abs(fft[0].toInt()) / 128f).coerceIn(0f, 1f)

            for (i in 1 until binCount) {
                val realIndex = i * 2
                val imagIndex = realIndex + 1
                if (imagIndex >= fft.size) break

                // 必须保留 signed byte 语义；不要 `and 0xFF`
                val real = fft[realIndex].toInt()
                val imag = fft[imagIndex].toInt()
                val magnitude = sqrt((real * real + imag * imag).toFloat())

                // 最大幅值约为 sqrt(128^2 + 128^2) ≈ 181，归一化到 0..1
                magnitudes[i] = (magnitude / 181f).coerceIn(0f, 1f)
            }
            return magnitudes
        }
    }

    private var previousVolume = 0f
    private var peakCooldownCounter = 0

    /**
     * 分析 FFT 数据
     */
    fun analyze(fft: ByteArray): AudioAnalysis {
        if (fft.size < 2) return AudioAnalysis()

        val magnitudes = fftToMagnitudes(fft, FFT_SIZE)
        val spectrumSize = magnitudes.size

        // 计算频段强度
        val bassEnd = BASS_END.coerceAtMost(spectrumSize)
        val midEnd = MID_END.coerceAtMost(spectrumSize)
        val trebleStart = TREBLE_START.coerceAtMost(spectrumSize)

        val bass = if (bassEnd > 0) {
            magnitudes.slice(0 until bassEnd).average().toFloat()
        } else 0f

        val mid = if (midEnd > bassEnd) {
            magnitudes.slice(bassEnd until midEnd).average().toFloat()
        } else 0f

        val treble = if (spectrumSize > trebleStart) {
            magnitudes.slice(trebleStart until spectrumSize).average().toFloat()
        } else 0f

        // 总音量
        val volume = magnitudes.average().toFloat()

        // 节拍检测（音量突增）
        val isPeak = if (peakCooldownCounter > 0) {
            peakCooldownCounter--
            false
        } else {
            val volumeDelta = volume - previousVolume
            val detected = volume > PEAK_THRESHOLD && volumeDelta > 0.15f
            if (detected) {
                peakCooldownCounter = PEAK_COOLDOWN
            }
            detected
        }

        previousVolume = volume

        return AudioAnalysis(
            bass = bass.coerceIn(0f, 1f),
            mid = mid.coerceIn(0f, 1f),
            treble = treble.coerceIn(0f, 1f),
            volume = volume.coerceIn(0f, 1f),
            isPeak = isPeak,
            rawSpectrum = magnitudes.toList()
        )
    }
}
