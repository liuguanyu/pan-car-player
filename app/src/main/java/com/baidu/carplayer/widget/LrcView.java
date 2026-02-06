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
    private float textSize = 48f; // 增大字体
    private int viewWidth;
    private int viewHeight;
    
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
        paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(textSize);
        paint.setColor(0xFF888888); // 灰色

        currentPaint = new Paint();
        currentPaint.setAntiAlias(true);
        currentPaint.setTextSize(textSize);
        currentPaint.setColor(0xFFFFFFFF); // 白色
        currentPaint.setFakeBoldText(true);
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
        
        // 绘制文本
        canvas.drawText(text, x, centerY, currentPaint);
    }
}