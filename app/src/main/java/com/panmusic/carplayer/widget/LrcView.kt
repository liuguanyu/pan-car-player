package com.panmusic.carplayer.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.panmusic.carplayer.util.LrcParser

/**
 * LRC 歌词显示 View
 *
 * 与 Java 版 LrcView 行为一致：
 * - 单行居中静态显示当前歌词（不滚动列表）
 * - 24sp 字号
 * - 当前行带流光渐变动画（3000ms 循环 REVERSE）
 * - 无歌词时不绘制任何内容
 */
class LrcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lrcLines: List<LrcParser.LrcLine> = emptyList()
    private var currentIndex: Int = -1

    private val textSize: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
    )

    private val normalPaint = Paint().apply {
        isAntiAlias = true
        this.textSize = this@LrcView.textSize
        color = 0xFF888888.toInt() // 灰色
    }

    private val currentPaint = Paint().apply {
        isAntiAlias = true
        this.textSize = this@LrcView.textSize
        color = Color.WHITE
        isFakeBoldText = true
    }

    // 流光渐变色（与 Java 版一致）
    private val shimmerColor1 = Color.rgb(255, 255, 255)
    private val shimmerColor2 = Color.rgb(200, 200, 255)
    private var shimmerOffset = 0f
    private var shimmerAnimator: ValueAnimator? = null

    private var viewWidth = 0
    private var viewHeight = 0

    init {
        startShimmerAnimation()
    }

    private fun startShimmerAnimation() {
        if (shimmerAnimator?.isRunning == true) return

        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                shimmerOffset = animation.animatedValue as Float
                if (currentIndex >= 0) {
                    invalidate()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shimmerAnimator?.cancel()
    }

    fun setLrc(lrcInfo: LrcParser.LrcInfo) {
        lrcLines = lrcInfo.lines
        currentIndex = -1
        invalidate()
    }

    fun updateTime(currentTimeMs: Long) {
        if (lrcLines.isEmpty()) return

        val newIndex = LrcParser.findCurrentLine(lrcLines, currentTimeMs)
        if (newIndex != currentIndex) {
            currentIndex = newIndex
            invalidate()
        }
    }

    fun reset() {
        lrcLines = emptyList()
        currentIndex = -1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 无歌词或未定位到当前行时不绘制
        if (lrcLines.isEmpty() || currentIndex < 0) return

        val text = lrcLines[currentIndex].text
        if (text.isEmpty()) return

        // 垂直居中
        val centerY = (viewHeight + textSize) / 2f

        // 计算文本宽度和居中位置
        val textWidth = currentPaint.measureText(text)
        val x = (viewWidth - textWidth) / 2f

        // 流光渐变（与 Java 版 updateShimmerGradient 一致）
        val offset = shimmerOffset * textWidth
        val textShader = LinearGradient(
            x - offset, 0f, x + textWidth - offset, textSize,
            intArrayOf(shimmerColor1, shimmerColor2, shimmerColor1),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.MIRROR
        )
        currentPaint.shader = textShader

        canvas.drawText(text, x, centerY, currentPaint)
    }
}
