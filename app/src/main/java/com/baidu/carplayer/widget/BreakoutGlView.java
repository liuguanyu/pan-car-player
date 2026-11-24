package com.baidu.carplayer.widget;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import com.baidu.carplayer.visualizer.VisualizerManager;
import com.baidu.carplayer.visualizer.breakout.BreakoutRenderer;

/** 音乐打砖块的 GLES 3.0 视图。播放中连续渲染，停止后由渲染器回调暂停 GL 线程。 */
public final class BreakoutGlView extends GLSurfaceView {
    private final BreakoutRenderer renderer = new BreakoutRenderer();
    private boolean active;
    private boolean continuous;

    public BreakoutGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer.setContext(context);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        renderer.setIdleListener(() -> post(this::stopContinuous));
    }

    public void attach(VisualizerManager m) {
        renderer.setFftSource(new BreakoutRenderer.FftSource() {
            @Override public byte[] latest() { return m.latestFft(); }
            @Override public int epoch() { return m.epoch(); }
        });
        renderer.setTempoSource(new BreakoutRenderer.TempoSource() {
            @Override public double beatPosition(long nowMs) { return m.beatPosition(nowMs); }
            @Override public float confidence(long nowMs) { return m.beatConfidence(nowMs); }
            @Override public float periodMs() { return m.periodMs(); }
        });
    }

    public void setActive(boolean v) {
        active = v;
        renderer.setWantActive(v);
        if (v) startContinuous();
    }

    private void startContinuous() {
        if (!continuous) {
            continuous = true;
            onResume();
            setRenderMode(RENDERMODE_CONTINUOUSLY);
        }
        requestRender();
    }

    private void stopContinuous() {
        if (active || !continuous) return;
        continuous = false;
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        onPause();
    }

    @Override protected void onDetachedFromWindow() {
        active = false;
        continuous = false;
        renderer.setWantActive(false);
        super.onDetachedFromWindow();
    }
}
