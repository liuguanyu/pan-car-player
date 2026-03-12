package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.baidu.carplayer.utils.LrcParser.LrcEntry;

import java.util.List;

/**
 * 歌词显示视图 - 静态居中显示当前歌词
 */
public class LrcView extends View {
    private List<LrcEntry> lrcEntries;
    private long currentTime = 0;
    private int currentLine = -1;

    private Paint paint;
    private Paint currentPaint;
    private float textSize; // 将在 init() 中初始化
    private int viewWidth;
    private int viewHeight;
    
    // 流光渐变色相关（与 PlayerActivity 标题流光动画颜色一致）
    private int shimmerColor1 = android.graphics.Color.rgb(255, 255, 255);
    private int shimmerColor2 = android.graphics.Color.rgb(200, 200, 255);
    private float shimmerOffset = 0f;
    private android.animation.ValueAnimator shimmerAnimator;
    
    // 滚动相关
    private float scrollX = 0f;

    public LrcView(Context context) {
        super(context);
        init();
    }

    public LrcView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LrcView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 使用 sp 单位转换像素，适度增大至 24sp
        textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                24f,
                getResources().getDisplayMetrics()
        );

        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(textSize);
        paint.setColor(0xFF888888); // 灰色

        currentPaint = new Paint();
        currentPaint.setAntiAlias(true);
        currentPaint.setTextSize(textSize);
        currentPaint.setColor(0xFFFFFFFF); // 白色
        currentPaint.setFakeBoldText(true);
        
        startShimmerAnimation();
    }

    private void startShimmerAnimation() {
        if (shimmerAnimator != null && shimmerAnimator.isRunning()) {
            return;
        }
        
        // 与 PlayerActivity 保持一致的动画配置
        shimmerAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        shimmerAnimator.setDuration(3000);
        shimmerAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        shimmerAnimator.addUpdateListener(animation -> {
            shimmerOffset = (float) animation.getAnimatedValue();
            if (currentLine >= 0) {
                invalidate();
            }
        });
        shimmerAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
        }
    }

    public void setLrcEntries(List<LrcEntry> lrcEntries) {
        this.lrcEntries = lrcEntries;
        currentLine = -1;
        invalidate();
    }

    public void updateTime(long time) {
        this.currentTime = time;
        
        if (lrcEntries == null || lrcEntries.isEmpty()) {
            return;
        }

        // 查找当前应该高亮的行
        int newCurrentLine = -1;
        for (int i = 0; i < lrcEntries.size(); i++) {
            if (lrcEntries.get(i).getTime() <= time) {
                newCurrentLine = i;
            } else {
                break;
            }
        }

        if (newCurrentLine != currentLine) {
            currentLine = newCurrentLine;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 如果没有歌词，不显示任何内容
        if (lrcEntries == null || lrcEntries.isEmpty() || currentLine < 0) {
            return;
        }

        // 垂直居中
        float centerY = (viewHeight + textSize) / 2f;
        
        // 简化逻辑：只显示当前歌词，居中静止显示
        
        // 获取当前歌词
        LrcEntry currentEntry = lrcEntries.get(currentLine);
        String text = currentEntry.getText();
        
        // 计算文本宽度
        float textWidth = currentPaint.measureText(text);
        
        // 计算居中位置
        float x = (viewWidth - textWidth) / 2f;
        
        // 与 PlayerActivity 的 updateShimmerGradient 保持完全一致的逻辑
        float offset = shimmerOffset * textWidth;
        
        android.graphics.Shader textShader = new android.graphics.LinearGradient(
            x - offset, 0, x + textWidth - offset, textSize,
            new int[]{shimmerColor1, shimmerColor2, shimmerColor1},
            new float[]{0f, 0.5f, 1f},
            android.graphics.Shader.TileMode.MIRROR
        );
        currentPaint.setShader(textShader);
        
        // 绘制文本
        canvas.drawText(text, x, centerY, currentPaint);
    }
}