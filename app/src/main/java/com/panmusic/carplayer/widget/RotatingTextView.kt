package com.panmusic.carplayer.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation

/** 唱片中心黄色标签上的环形旋转文字。 */
class RotatingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var text: String = ""
    private var rotateAnimation: RotateAnimation? = null
    private var isRotating = false
    private val textPath = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 153
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.15f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (text.isEmpty()) return

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(width, height) / 2f
        val textRadius = radius * 0.175f
        textPaint.textSize = radius * 0.07f

        textPath.reset()
        textPath.addCircle(centerX, centerY, textRadius, Path.Direction.CW)
        canvas.drawTextOnPath(text, textPath, 0f, textPaint.textSize * 0.35f, textPaint)
    }

    fun setText(value: String?) {
        text = value.orEmpty()
        invalidate()
    }

    fun startRotation() = resumeRotation()

    fun pauseRotation() {
        if (!isRotating) return
        isRotating = false
        rotateAnimation?.cancel()
    }

    fun resumeRotation() {
        if (isRotating) return
        isRotating = true
        rotateAnimation = RotateAnimation(
            0f,
            -360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        ).apply {
            duration = 12_000L
            repeatCount = Animation.INFINITE
            repeatMode = Animation.RESTART
            interpolator = LinearInterpolator()
        }
        startAnimation(rotateAnimation)
    }

    fun stopRotation() {
        if (!isRotating && rotateAnimation == null) return
        isRotating = false
        rotateAnimation?.cancel()
        rotateAnimation = null
        clearAnimation()
        rotation = 0f
    }

    override fun onDetachedFromWindow() {
        stopRotation()
        super.onDetachedFromWindow()
    }
}
