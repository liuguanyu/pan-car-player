package com.baidu.carplayer.visualizer;

import android.graphics.*;

/** Green/cyan/blue spectrum with fast attack and slow decay, matching the Kotlin effect. */
public final class BarsVisualizer implements AudioVisualizer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] magnitudes = new float[0];
    private int width, height, gradientWidth = -1;
    private LinearGradient gradient;

    @Override public String getName() { return "bars"; }
    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, 64);
        if (magnitudes.length != raw.length) magnitudes = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            float target = (float) Math.sqrt(raw[i]);
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
            canvas.drawRect(x, height - magnitudes[i] * height * .7f, x + barWidth, height, paint);
        }
        paint.setShader(null);
    }
    @Override public void reset() { magnitudes = new float[0]; }
}
