package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;

import androidx.annotation.Nullable;

/**
 * 旋转文字视图
 * 透明背景，只显示旋转的文字
 */
public class RotatingTextView extends View {

    private String text = "";
    private Paint textPaint;
    private Path textPath;
    private RotateAnimation rotateAnimation;
    private boolean isRotating = false;
    private float textRadius; // 缓存的文字半径

    public RotatingTextView(Context context) {
        super(context);
        init();
    }

    public RotatingTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RotatingTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 初始化画笔
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setAlpha(153); // 设置透明度为0.6 (153/255 ≈ 0.6)
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setLetterSpacing(0.15f); // 增加字符间距，0.15表示字符间距是字体大小的15%
        
        textPath = new Path();
        
        // 预计算黄圈环形中点半径
        // 黄圈装饰(labelShader)半径: 0.27f, 中心孔半径: 0.08f
        // 我们需要把文字放在这个黄色区域内，取中间值 (0.27 + 0.08) / 2 = 0.175f
        textRadius = 0.175f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (text == null || text.isEmpty()) {
            return;
        }
        
        int width = getWidth();
        int height = getHeight();
        float centerX = width / 2f;
        float centerY = height / 2f;
        float radius = Math.min(width, height) / 2f;
        
        // 使用预计算的环形中点半径
        float actualTextRadius = radius * textRadius;
        textPath.reset();
        textPath.addCircle(centerX, centerY, actualTextRadius, Path.Direction.CW);
        
        // 根据半径调整字体大小
        textPaint.setTextSize(radius * 0.07f);
        
        // 绘制文字沿着圆形路径
        // 使用正偏移量帮助垂直居中 (向下偏移)
        canvas.drawTextOnPath(text, textPath, 0, textPaint.getTextSize() * 0.35f, textPaint);
    }

    /**
     * 开始旋转
     */
    public void startRotation() {
        if (isRotating) {
            return;
        }

        isRotating = true;
        
        // 创建旋转动画（逆时针旋转）
        rotateAnimation = new RotateAnimation(
            0f, -360f,  // 负值表示逆时针旋转
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        );
        rotateAnimation.setDuration(12000); // 12秒一圈，更缓慢的旋转速度
        rotateAnimation.setRepeatCount(Animation.INFINITE);
        rotateAnimation.setRepeatMode(Animation.RESTART);
        rotateAnimation.setInterpolator(new android.view.animation.LinearInterpolator()); // 匀速旋转
        
        startAnimation(rotateAnimation);
    }

    /**
     * 停止旋转
     */
    public void stopRotation() {
        if (!isRotating) {
            return;
        }

        isRotating = false;
        
        if (rotateAnimation != null) {
            rotateAnimation.cancel();
            clearAnimation();
        }
        
        // 重置旋转角度
        setRotation(0f);
    }

    /**
     * 暂停旋转
     */
    public void pauseRotation() {
        if (!isRotating) {
            return;
        }

        isRotating = false;
        
        if (rotateAnimation != null) {
            rotateAnimation.cancel();
        }
    }

    /**
     * 恢复旋转
     */
    public void resumeRotation() {
        if (isRotating) {
            return;
        }
        
        startRotation();
    }

    /**
     * 检查是否正在旋转
     */
    public boolean isRotating() {
        return isRotating;
    }

    /**
     * 设置文字
     */
    public void setText(String text) {
        this.text = text != null ? text : "";
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // View 销毁时停止动画，防止内存泄漏
        stopRotation();
    }
}