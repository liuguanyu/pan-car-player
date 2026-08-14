package com.panmusic.carplayer.visualizer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音频粒子可视化 — 低频 > 阈值时从中心爆射粒子，中心圆随低频脉动
 */
class ParticlesVisualizer : AudioVisualizer {

    override val name = "particles"

    private var canvasWidth = 0
    private var canvasHeight = 0
    private var bass = 0f
    private val analyzer = AudioAnalyzer()

    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var maxLife: Float,
        var size: Float
    )

    private val particles = mutableListOf<Particle>()
    private val maxParticles = 80

    private val particlePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val circleColors = intArrayOf(
        Color.argb(180, 255, 150, 0),
        Color.argb(100, 255, 100, 0),
        Color.argb(0, 255, 50, 0)
    )

    override fun update(fft: ByteArray, width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height

        val analysis = analyzer.analyze(fft)
        if (analysis.rawSpectrum.isEmpty()) {
            // 无音频数据：使用默认低频值，维持中心圆脉动
            bass = 0.3f
            return
        }

        // 低频做轻微平滑，避免粒子乱炸
        val newBass = bass + (analysis.bass - bass) * if (analysis.bass > bass) 0.45f else 0.18f

        // 爆射粒子：只在低频显著突增时触发，而不是根据错误放大的随机频点触发
        if (analysis.isPeak || (newBass > 0.45f && bass <= 0.45f)) {
            burstParticles(newBass.coerceAtLeast(0.35f))
        }
        bass = newBass.coerceIn(0f, 1f)
    }

    private fun burstParticles(intensity: Float) {
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val count = (intensity * 20).toInt().coerceAtLeast(5)

        repeat(count) {
            val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = (100f + intensity * 500f) * Random.nextFloat()
            val life = (20f + intensity * 40f)

            particles.add(
                Particle(
                    x = cx,
                    y = cy,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    life = life,
                    maxLife = life,
                    size = 2f + Random.nextFloat() * 6f
                )
            )
        }

        // 限制最大粒子数
        while (particles.size > maxParticles) {
            particles.removeAt(0)
        }
    }

    override fun draw(canvas: Canvas) {
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f

        // 中心脉动圆
        val circleRadius = 30f + bass * 60f
        centerPaint.shader = RadialGradient(
            cx, cy, circleRadius * 2f,
            circleColors, null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, circleRadius * 2f, centerPaint)
        centerPaint.shader = null

        // 绘制粒子
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx * 0.016f // ~60fps delta
            p.y += p.vy * 0.016f
            p.life--

            if (p.life <= 0) {
                iterator.remove()
                continue
            }

            val progress = p.life / p.maxLife
            val alpha = (progress * 255).toInt().coerceIn(0, 255)
            particlePaint.color = Color.argb(
                alpha,
                (255 * (1f - progress)).toInt(),
                (200 * progress).toInt(),
                (255 * progress).toInt()
            )
            particlePaint.shader = RadialGradient(
                p.x, p.y, p.size * 2f,
                Color.argb(alpha, 100, 200, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(p.x, p.y, p.size, particlePaint)
        }
        particlePaint.shader = null
    }

    override fun reset() {
        particles.clear()
        bass = 0f
    }
}
