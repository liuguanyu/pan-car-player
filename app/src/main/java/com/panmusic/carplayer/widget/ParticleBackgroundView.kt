package com.panmusic.carplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Java 版轻量粒子背景的 Kotlin 迁移版。
 *
 * 粒子对象固定复用；播放时 60fps，暂停时 15fps，不可见/销毁时停止重绘。
 */
class ParticleBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val PARTICLE_COUNT = 25
        private const val BASE_SPEED = 0.5f
        private const val PLAYING_SPEED_MULTIPLIER = 2.0f
        private const val PLAYING_FRAME_DELAY_MS = 16L
        private const val PAUSED_FRAME_DELAY_MS = 66L
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(System.currentTimeMillis())

    private val colors = intArrayOf(
        0xFF00FFFF.toInt(),
        0xFF00BFFF.toInt(),
        0xFF9370DB.toInt(),
        0xFFFF69B4.toInt()
    )

    private val particles = Array(PARTICLE_COUNT) { Particle() }
    private var isPlaying = false
    private var shouldAnimate = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        particles.forEach { it.reset(w, h) }
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
        shouldAnimate = true
        if (isShown) invalidate()
    }

    fun startAnimation() {
        shouldAnimate = true
        invalidate()
    }

    fun stopAnimation() {
        shouldAnimate = false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        shouldAnimate = visibility == VISIBLE
        if (shouldAnimate) invalidate()
    }

    override fun onDetachedFromWindow() {
        shouldAnimate = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val speedMultiplier = if (isPlaying) PLAYING_SPEED_MULTIPLIER else 1f
        particles.forEach { particle ->
            particle.update(width, height, speedMultiplier)
            particle.draw(canvas, particlePaint)
        }

        if (shouldAnimate && isShown) {
            postInvalidateDelayed(if (isPlaying) PLAYING_FRAME_DELAY_MS else PAUSED_FRAME_DELAY_MS)
        }
    }

    private inner class Particle {
        private var x = 0f
        private var y = 0f
        private var vx = 0f
        private var vy = 0f
        private var radius = 0f
        private var alpha = 0f
        private var alphaSpeed = 0f
        private var color = colors[random.nextInt(colors.size)]

        fun reset(width: Int, height: Int) {
            x = random.nextFloat() * width
            y = random.nextFloat() * height
            val angle = random.nextFloat() * Math.PI.toFloat() * 2f
            val speed = BASE_SPEED + random.nextFloat() * BASE_SPEED
            vx = cos(angle) * speed
            vy = sin(angle) * speed
            radius = 2f + random.nextFloat() * 4f
            alpha = 50f + random.nextInt(100)
            alphaSpeed = 0.5f + random.nextFloat()
            color = colors[random.nextInt(colors.size)]
        }

        fun update(width: Int, height: Int, speedMultiplier: Float) {
            x += vx * speedMultiplier
            y += vy * speedMultiplier

            if (x < -radius) x = width + radius
            if (x > width + radius) x = -radius
            if (y < -radius) y = height + radius
            if (y > height + radius) y = -radius

            alpha += alphaSpeed
            if (alpha > 150f || alpha < 30f) {
                alphaSpeed = -alphaSpeed
                alpha = alpha.coerceIn(30f, 150f)
            }
        }

        fun draw(canvas: Canvas, paint: Paint) {
            paint.shader = null
            paint.color = color
            paint.alpha = alpha.toInt()
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
