package com.baidu.carplayer.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import com.baidu.carplayer.visualizer.BarsGlRenderer;
import com.baidu.carplayer.visualizer.VisualizerManager;

/**
 * 3D 频谱柱的 GLES 3.0 视图。播放中连续渲染；静止后回落即暂停 GL 线程。
 * shader 存放在 res/raw（经 {@link GlslLoader} 加载），不再用字符串拼接。
 */
public final class BarsGlView extends GLSurfaceView {
    private final BarsGlRenderer renderer = new BarsGlRenderer();
    private boolean active;
    private boolean continuous;

    public BarsGlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        setPreserveEGLContextOnPause(true);
        renderer.setContext(context);   // 先注入 Context，再挂 renderer
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        renderer.setIdleListener(() -> post(this::stopContinuous));
    }

    /** 设备是否支持 GLES 3.0。 */
    public static boolean isSupported(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ConfigurationInfo info = am.getDeviceConfigurationInfo();
        return info != null && info.reqGlEsVersion >= 0x30000;
    }

    public void attach(VisualizerManager m) {
        renderer.setFftSource(new BarsGlRenderer.FftSource() {
            @Override public byte[] latest() { return m.latestFft(); }
            @Override public int epoch() { return m.epoch(); }
        });
        renderer.setTempoSource(m::beatPosition);
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
