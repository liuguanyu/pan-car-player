package com.baidu.carplayer.visualizer;

import android.graphics.*;

/** Mirrored cyan FFT curve, ported from WaveVisualizer.kt. */
public final class WaveVisualizer implements AudioVisualizer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private float[] magnitudes = new float[0];
    private int width, height;
    public WaveVisualizer() {
        paint.setColor(0xff00e5ff); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2); paint.setStrokeJoin(Paint.Join.ROUND); paint.setStrokeCap(Paint.Cap.ROUND);
    }
    @Override public String getName() { return "wave"; }
    @Override public void update(byte[] fft, int width, int height) {
        this.width = width; this.height = height;
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, 128);
        if (magnitudes.length != raw.length) magnitudes = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            float target = (float) Math.sqrt(raw[i]);
            magnitudes[i] += (target - magnitudes[i]) * (target > magnitudes[i] ? .5f : .18f);
        }
    }
    @Override public void draw(Canvas canvas) {
        if (magnitudes.length == 0 || width <= 0 || height <= 0) return;
        path.reset();
        float center = height / 2f, amplitude = height * .4f, step = (float) width / magnitudes.length;
        path.moveTo(0, center);
        for (int i = 0; i < magnitudes.length; i++)
            path.lineTo(i * step, center - magnitudes[i] * amplitude + (.5f - magnitudes[i]) * amplitude * .5f);
        for (int i = magnitudes.length - 1; i >= 0; i--)
            path.lineTo(i * step, center + magnitudes[i] * amplitude - (.5f - magnitudes[i]) * amplitude * .5f);
        path.close(); canvas.drawPath(path, paint);
    }
    @Override public void reset() { magnitudes = new float[0]; }
}
