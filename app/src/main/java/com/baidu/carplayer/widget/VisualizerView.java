package com.baidu.carplayer.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import com.baidu.carplayer.visualizer.VisualizerManager;

/** UI-thread drawing loop; no capture or animation work while hidden. */
public final class VisualizerView extends View {
    private VisualizerManager manager;
    private boolean active;
    public VisualizerView(Context context, AttributeSet attrs) { super(context, attrs); }
    public void attach(VisualizerManager manager) { this.manager = manager; }
    public void setActive(boolean value) {
        active = value;
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (manager != null) manager.draw(canvas, getWidth(), getHeight());
        // 播放中持续绘制；停止后继续绘制到效果回落为 0 态，然后停帧。
        if (isShown() && (active || (manager != null && manager.isSettling()))) postInvalidateOnAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        active = false;
        if (manager != null) manager.release();
        super.onDetachedFromWindow();
    }
}
