package com.baidu.carplayer.visualizer;

import android.graphics.*;

/** Green/cyan/blue spectrum with fast attack and slow decay, matching the Kotlin effect. */
public final class BarsVisualizer implements AudioVisualizer {
    static final int BAR_COUNT = 64;
    /** 静止态保留的最小柱高（px），让 0 态仍可见一条基线。 */
    private static final float MIN_BAR = 2f;
    private static final float REST = .004f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] magnitudes = new float[0];
    private int width, height, gradientWidth = -1;
    private LinearGradient gradient;

    @Override public String getName() { return "bars"; }
    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, BAR_COUNT);
        // 没有数据（暂停/停止）时以 0 为目标衰减，而不是立刻清空。
        if (magnitudes.length != BAR_COUNT) magnitudes = new float[BAR_COUNT];
        for (int i = 0; i < BAR_COUNT; i++) {
            float target = i < raw.length ? (float) Math.sqrt(raw[i]) : 0f;
            magnitudes[i] += (target - magnitudes[i]) * (target > magnitudes[i] ? .55f : .22f);
        }
    }
    @Override public void draw(Canvas canvas) {
        if (width <= 0 || magnitudes.length == 0) return;
        if (gradientWidth != width) {
            gradient = new LinearGradient(0, 0, width, 0,
                    new int[]{0xff00ff00, 0xff00e5ff, 0xff2979ff}, null, Shader.TileMode.CLAMP);
            gradientWidth = width;
        }
        paint.setShader(gradient);
        float gap = 2, barWidth = Math.max(0, (width - gap * (magnitudes.length + 1)) / magnitudes.length);
        for (int i = 0; i < magnitudes.length; i++) {
            float x = gap + i * (barWidth + gap);
            float barHeight = Math.max(MIN_BAR, magnitudes[i] * height * .9f);
            canvas.drawRect(x, height - barHeight, x + barWidth, height, paint);
        }
        paint.setShader(null);
    }
    @Override public void reset() { magnitudes = new float[0]; }
    @Override public boolean isAtRest() {
        for (float m : magnitudes) if (m > REST) return false;
        return true;
    }
}
