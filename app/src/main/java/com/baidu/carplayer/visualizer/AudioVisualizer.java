package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;

/** Java port of the Kotlin branch's audio visualization contract. */
public interface AudioVisualizer {
    void update(byte[] fft, int width, int height);
    void draw(Canvas canvas);
    void reset();

    /** 是否已回到静止（0 态）。停止播放后持续以空数据更新，直到返回 true 才停止绘制。 */
    boolean isAtRest();
    String getName();
}
