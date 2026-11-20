package com.baidu.carplayer.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import com.baidu.carplayer.visualizer.ParticleGlRenderer;
import com.baidu.carplayer.visualizer.VisualizerManager;

/**
 * 粒子可视化的 GLES 3.0 视图。播放中连续渲染；停止后场景回落到静止就切回按需渲染并暂停 GL 线程，
 * 不再消耗 GPU。FFT 与节拍由 {@link VisualizerManager} 提供。
 */
public final class ParticleGlView extends GLSurfaceView {
    private final ParticleGlRenderer renderer = new ParticleGlRenderer();
    private boolean active;
    private boolean continuous;

    public ParticleGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer.setContext(context);   // 先注入 Context（读 res/raw GLSL），再挂 renderer
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        renderer.setIdleListener(() -> post(this::stopContinuous));
    }

    /** 设备是否支持 GLES 3.0（Android 10 车机均支持）。 */
    public static boolean isSupported(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ConfigurationInfo info = am.getDeviceConfigurationInfo();
        return info != null && info.reqGlEsVersion >= 0x30000;
    }

    public void attach(VisualizerManager manager) {
        renderer.setFftSource(new ParticleGlRenderer.FftSource() {
            @Override public byte[] latest() { return manager.latestFft(); }
            @Override public int epoch() { return manager.epoch(); }
        });
        renderer.setTempoSource(manager::periodMs);
    }

    public void setActive(boolean value) {
        active = value;
        renderer.setWantActive(value);
        if (value) startContinuous();
        // 停止播放：继续渲染直到场景回落（IdleListener 触发 stopContinuous）
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
        renderer.setWantActive(false);
        continuous = false;
        super.onDetachedFromWindow();
    }
}
