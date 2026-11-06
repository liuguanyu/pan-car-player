package com.baidu.carplayer.visualizer;

import android.graphics.Canvas;

/** Java port of the Kotlin branch's audio visualization contract. */
public interface AudioVisualizer {
    void update(byte[] fft, int width, int height);
    void draw(Canvas canvas);
    void reset();
    String getName();
}
