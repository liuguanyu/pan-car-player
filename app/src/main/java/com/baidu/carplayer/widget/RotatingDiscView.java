package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * 旋转唱片视图
 * 类似网易云音乐的唱片旋转效果
 */
public class RotatingDiscView extends ImageView {

    private boolean isRotating = false;
    private float currentRotation = 0f;
    private String title = "";
    private ValueAnimator rotationAnimator;

    public RotatingDiscView(Context context) {
        super(context);
        init();
    }

    public RotatingDiscView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RotatingDiscView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private Paint discPaint;
    private Paint groovePaint;
    private Paint labelPaint;
    private Paint centerPaint;
    private Paint lightPaint;
    private Paint shadowPaint;
    private Paint edgePaint;
    private Paint textPaint;
    private RectF discRect;
    private Path clipPath;
    private Path textPath;
    
    // 缓存的 Shader 对象
    private Shader shadowShader;
    private Shader discShader;
    private Shader labelBgShader;
    private Shader labelShader;
    private Shader lightShader;
    
    // 缓存的尺寸信息
    private float centerX;
    private float centerY;
    private float radius;
    private float[] grooveRadii;

    private void init() {
        // 添加阴影效果
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            setElevation(8f);
        }
        
        // 初始化画笔
        discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        groovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        groovePaint.setStyle(Paint.Style.STROKE);
        groovePaint.setStrokeWidth(0.5f);
        
        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        
        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.BLACK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        
        discRect = new RectF();
        clipPath = new Path();
        textPath = new Path();
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        int size = Math.min(w, h);
        if (size == 0) return;
        
        centerX = w / 2f;
        centerY = h / 2f;
        radius = size / 2f;
        
        // 预计算凹槽半径
        grooveRadii = new float[10];
        int index = 0;
        for (int i = 8; i < 35 && index < grooveRadii.length; i += 3) {
            float grooveRadius = radius * (0.95f - i * 0.02f);
            if (grooveRadius > radius * 0.32f) {
                grooveRadii[index++] = grooveRadius;
            }
        }
        
        // 预创建 Shader 对象
        createShaders();
    }
    
    private void createShaders() {
        // 阴影 Shader
        shadowShader = new RadialGradient(
            centerX, centerY, radius,
            new int[]{0x66000000, 0x00000000},
            new float[]{0.95f, 1.0f},
            Shader.TileMode.CLAMP
        );
        
        // 唱片主体 Shader
        discShader = new RadialGradient(
            centerX, centerY, radius * 0.95f,
            new int[]{0xFF1a1a1a, 0xFF0a0a0a, 0xFF1a1a1a},
            new float[]{0f, 0.5f, 1.0f},
            Shader.TileMode.CLAMP
        );
        
        // 标签背景 Shader
        labelBgShader = new RadialGradient(
            centerX, centerY, radius * 0.32f,
            new int[]{0xFF2a2a2a, 0xFF1a1a1a},
            new float[]{0.7f, 1.0f},
            Shader.TileMode.CLAMP
        );
        
        // 标签装饰 Shader
        labelShader = new RadialGradient(
            centerX, centerY, radius * 0.27f,
            new int[]{0xFFFFD700, 0xFFCC8800, 0xFF996600},
            new float[]{0f, 0.7f, 1.0f},
            Shader.TileMode.CLAMP
        );
        
        // 光照 Shader - 模拟从左上角45度照射
        float lightCenterX = centerX - radius * 0.4f;
        float lightCenterY = centerY - radius * 0.4f;
        float lightRadius = radius * 0.8f;
        
        lightShader = new RadialGradient(
            lightCenterX, lightCenterY, lightRadius,
            new int[]{0x40FFFFFF, 0x20FFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.5f, 1.0f},
            Shader.TileMode.CLAMP
        );
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        if (radius == 0) return;
        
        // 设置圆形裁剪路径
        clipPath.reset();
        clipPath.addCircle(centerX, centerY, radius, Path.Direction.CW);
        
        // 保存画布状态
        int saveCount = canvas.save();
        canvas.clipPath(clipPath);
        
        // 1. 绘制外圈阴影（不旋转）
        shadowPaint.setShader(shadowShader);
        canvas.drawCircle(centerX, centerY, radius, shadowPaint);
        
        // 2. 绘制唱片主体（不旋转）
        discPaint.setShader(discShader);
        canvas.drawCircle(centerX, centerY, radius * 0.95f, discPaint);
        
        // 3. 绘制唱片凹槽纹理
        groovePaint.setColor(0x15FFFFFF);
        groovePaint.setStrokeWidth(0.5f);
        for (int i = 0; i < grooveRadii.length && grooveRadii[i] > 0; i++) {
            groovePaint.setAlpha((int)(25 + (i * 3) % 30));
            canvas.drawCircle(centerX, centerY, grooveRadii[i], groovePaint);
        }
        
        // 4. 绘制标签区域
        labelPaint.setShader(labelBgShader);
        canvas.drawCircle(centerX, centerY, radius * 0.32f, labelPaint);
        
        // 5. 绘制标签装饰
        labelPaint.setShader(labelShader);
        canvas.drawCircle(centerX, centerY, radius * 0.27f, labelPaint);

        // 移除原来的文字绘制代码，文字将由RotatingTextView处理
        
        // 6. 绘制中心孔
        centerPaint.setColor(0xFF000000);
        canvas.drawCircle(centerX, centerY, radius * 0.08f, centerPaint);
        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(1.5f);
        centerPaint.setColor(0x66FFFFFF);
        canvas.drawCircle(centerX, centerY, radius * 0.08f, centerPaint);
        centerPaint.setStyle(Paint.Style.FILL);
        
        // 7. 绘制斜角光照效果（不旋转，固定光源）
        lightPaint.setShader(lightShader);
        float lightCenterX = centerX - radius * 0.4f;
        float lightCenterY = centerY - radius * 0.4f;
        float lightRadius = radius * 0.8f;
        canvas.drawCircle(lightCenterX, lightCenterY, lightRadius, lightPaint);
        
        // 8. 添加边缘微光（不旋转）
        edgePaint.setColor(0x18FFFFFF);
        edgePaint.setStrokeWidth(2f);
        edgePaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(centerX, centerY, radius * 0.94f, edgePaint);
        
        // 恢复画布状态
        canvas.restoreToCount(saveCount);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        
        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        
        // 保持正方形比例，取较小的一边作为边长
        int size = Math.min(width, height);
        
        // 如果尺寸为0，则使用默认测量结果
        if (size == 0) {
            size = Math.max(width, height);
        }
        
        setMeasuredDimension(size, size);
    }

    /**
     * 开始旋转
     */
    public void startRotation() {
        if (isRotating) {
            return;
        }

        isRotating = true;
        
        // 创建 ValueAnimator 实现平滑旋转
        if (rotationAnimator == null) {
            rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
            rotationAnimator.setDuration(2000); // 2秒一圈，模拟45转唱片速度
            rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
            rotationAnimator.setInterpolator(new LinearInterpolator());
            rotationAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    currentRotation = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
        }
        
        if (!rotationAnimator.isRunning()) {
            rotationAnimator.start();
        }
    }

    /**
     * 停止旋转
     */
    public void stopRotation() {
        if (!isRotating) {
            return;
        }

        isRotating = false;
        currentRotation = 0f; // 重置角度
        
        if (rotationAnimator != null && rotationAnimator.isRunning()) {
            rotationAnimator.cancel();
        }
    }

    /**
     * 暂停旋转（保持当前角度）
     */
    public void pauseRotation() {
        if (!isRotating) {
            return;
        }

        isRotating = false;
        
        if (rotationAnimator != null && rotationAnimator.isRunning()) {
            rotationAnimator.pause();
        }
    }

    /**
     * 恢复旋转
     */
    public void resumeRotation() {
        if (isRotating) {
            return;
        }
        
        isRotating = true;
        
        if (rotationAnimator != null) {
            if (rotationAnimator.isPaused()) {
                rotationAnimator.resume();
            } else if (!rotationAnimator.isRunning()) {
                rotationAnimator.start();
            }
        } else {
            startRotation(); // 如果动画从未创建过，重新创建
        }
    }

    /**
     * 检查是否正在旋转
     */
    public boolean isRotating() {
        return isRotating;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // View 销毁时停止动画，防止内存泄漏
        isRotating = false;
    }

    /**
     * 设置唱片标题
     */
    public void setTitle(String title) {
        if (title == null) {
            this.title = "";
        } else {
            // 去除文件后缀名 (如 .mp3, .flac 等)
            int lastDotIndex = title.lastIndexOf('.');
            if (lastDotIndex > 0) {
                this.title = title.substring(0, lastDotIndex);
            } else {
                this.title = title;
            }
        }
        invalidate();
    }
}