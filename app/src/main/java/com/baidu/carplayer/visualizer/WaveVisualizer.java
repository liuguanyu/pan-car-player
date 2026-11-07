package com.baidu.carplayer.visualizer;

import android.graphics.*;

/** Mirrored cyan FFT curve, ported from WaveVisualizer.kt. */
public final class WaveVisualizer implements AudioVisualizer {
    static final int POINTS = 128;
    private static final float REST = .004f;
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
        float[] raw = AudioAnalyzer.fftToMagnitudes(fft, POINTS);
        if (magnitudes.length != POINTS) magnitudes = new float[POINTS];
        for (int i = 0; i < POINTS; i++) {
            float target = i < raw.length ? (float) Math.sqrt(raw[i]) : 0f;
            magnitudes[i] += (target - magnitudes[i]) * (target > magnitudes[i] ? .5f : .18f);
        }
    }
    @Override public void draw(Canvas canvas) {
        if (magnitudes.length == 0 || width <= 0 || height <= 0) return;
        path.reset();
        // 以中线上下镜像；全部为 0 时收敛成一条水平线（0 态）。
        float center = height / 2f, amplitude = height * .45f, step = (float) width / (magnitudes.length - 1);
        path.moveTo(0, center);
        for (int i = 0; i < magnitudes.length; i++)
            path.lineTo(i * step, center - magnitudes[i] * amplitude);
        for (int i = magnitudes.length - 1; i >= 0; i--)
            path.lineTo(i * step, center + magnitudes[i] * amplitude);
        path.close(); canvas.drawPath(path, paint);
    }
    @Override public void reset() { magnitudes = new float[0]; }
    @Override public boolean isAtRest() {
        for (float m : magnitudes) if (m > REST) return false;
        return true;
    }
}
