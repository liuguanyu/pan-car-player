package com.panmusic.carplayer.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Java 版唱片视图的 Kotlin 迁移版。
 *
 * 只在尺寸变化时重建 Shader/半径缓存，绘制帧内不创建图形对象，满足迁移文档的 Canvas 性能约束。
 */
class RotatingDiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isRotating = false
    private var rotationAnimator: ValueAnimator? = null

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var grooveRadii = FloatArray(0)

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val clipPath = Path()

    private var shadowShader: Shader? = null
    private var discShader: Shader? = null
    private var labelBgShader: Shader? = null
    private var labelShader: Shader? = null
    private var lightShader: Shader? = null

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            elevation = 8f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val measuredSize = min(measuredWidth, measuredHeight).takeIf { it > 0 }
            ?: maxOf(measuredWidth, measuredHeight)
        setMeasuredDimension(measuredSize, measuredSize)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(w, h)
        if (size <= 0) return

        centerX = w / 2f
        centerY = h / 2f
        radius = size / 2f

        val radii = mutableListOf<Float>()
        for (i in 8 until 35 step 3) {
            val grooveRadius = radius * (0.95f - i * 0.02f)
            if (grooveRadius > radius * 0.32f) radii += grooveRadius
        }
        grooveRadii = radii.toFloatArray()
        createShaders()
    }

    private fun createShaders() {
        shadowShader = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(0x66000000, 0x00000000),
            floatArrayOf(0.95f, 1f),
            Shader.TileMode.CLAMP
        )
        discShader = RadialGradient(
            centerX, centerY, radius * 0.95f,
            intArrayOf(0xFF1A1A1A.toInt(), 0xFF0A0A0A.toInt(), 0xFF1A1A1A.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        labelBgShader = RadialGradient(
            centerX, centerY, radius * 0.32f,
            intArrayOf(0xFF2A2A2A.toInt(), 0xFF1A1A1A.toInt()),
            floatArrayOf(0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        labelShader = RadialGradient(
            centerX, centerY, radius * 0.27f,
            intArrayOf(0xFFFFD700.toInt(), 0xFFCC8800.toInt(), 0xFF996600.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        lightShader = RadialGradient(
            centerX - radius * 0.4f,
            centerY - radius * 0.4f,
            radius * 0.8f,
            intArrayOf(0x40FFFFFF, 0x20FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        clipPath.reset()
        clipPath.addCircle(centerX, centerY, radius, Path.Direction.CW)

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        shadowPaint.shader = shadowShader
        canvas.drawCircle(centerX, centerY, radius, shadowPaint)

        discPaint.shader = discShader
        canvas.drawCircle(centerX, centerY, radius * 0.95f, discPaint)

        groovePaint.color = Color.WHITE
        groovePaint.strokeWidth = 0.5f
        grooveRadii.forEachIndexed { index, grooveRadius ->
            groovePaint.alpha = 25 + (index * 3) % 30
            canvas.drawCircle(centerX, centerY, grooveRadius, groovePaint)
        }

        labelPaint.shader = labelBgShader
        canvas.drawCircle(centerX, centerY, radius * 0.32f, labelPaint)

        labelPaint.shader = labelShader
        canvas.drawCircle(centerX, centerY, radius * 0.27f, labelPaint)

        centerPaint.style = Paint.Style.FILL
        centerPaint.shader = null
        centerPaint.color = Color.BLACK
        canvas.drawCircle(centerX, centerY, radius * 0.08f, centerPaint)

        centerPaint.style = Paint.Style.STROKE
        centerPaint.strokeWidth = 1.5f
        centerPaint.color = 0x66FFFFFF
        canvas.drawCircle(centerX, centerY, radius * 0.08f, centerPaint)

        lightPaint.shader = lightShader
        canvas.drawCircle(
            centerX - radius * 0.4f,
            centerY - radius * 0.4f,
            radius * 0.8f,
            lightPaint
        )

        edgePaint.color = 0x18FFFFFF
        edgePaint.strokeWidth = 2f
        canvas.drawCircle(centerX, centerY, radius * 0.94f, edgePaint)

        canvas.restoreToCount(saveCount)
    }

    fun startRotation() {
        resumeRotation()
    }

    fun pauseRotation() {
        if (!isRotating) return
        isRotating = false
        rotationAnimator?.pause()
    }

    fun resumeRotation() {
        if (isRotating) return
        isRotating = true
        val animator = rotationAnimator ?: ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                rotation = animation.animatedValue as Float
            }
            rotationAnimator = this
        }
        if (animator.isPaused) {
            animator.resume()
        } else if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopRotation() {
        isRotating = false
        rotationAnimator?.cancel()
        rotation = 0f
    }

    fun isRotating(): Boolean = isRotating

    override fun onDetachedFromWindow() {
        stopRotation()
        rotationAnimator = null
        super.onDetachedFromWindow()
    }
}
