package com.baidu.carplayer.widget;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import com.baidu.carplayer.visualizer.ThunderGlRenderer;
import com.baidu.carplayer.visualizer.VisualizerManager;

/** GLES 3.0 雷雨视图。 */
public final class ThunderGlView extends GLSurfaceView {
    private final ThunderGlRenderer renderer = new ThunderGlRenderer();
    private boolean active, continuous;
    public ThunderGlView(Context c, AttributeSet a) {
        super(c, a); setEGLContextClientVersion(3); setEGLConfigChooser(8,8,8,8,0,0);
        setPreserveEGLContextOnPause(true); setRenderer(renderer); setRenderMode(RENDERMODE_WHEN_DIRTY);
        renderer.setIdleListener(() -> post(this::stop));
    }
    public static boolean isSupported(Context c) { ActivityManager a=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE); ConfigurationInfo i=a==null?null:a.getDeviceConfigurationInfo(); return i!=null&&i.reqGlEsVersion>=0x30000; }
    public void attach(VisualizerManager m) {
        renderer.setFftSource(new ThunderGlRenderer.FftSource(){public byte[] latest(){return m.latestFft();}public int epoch(){return m.epoch();}});
        renderer.setTempoSource(new ThunderGlRenderer.TempoSource(){public double beatPosition(long n){return m.beatPosition(n);}public float periodMs(){return m.periodMs();}public float confidence(long n){return m.beatConfidence(n);}});
    }
    public void setActive(boolean v){active=v;renderer.setWantActive(v);if(v)start();}
    private void start(){if(!continuous){continuous=true;onResume();setRenderMode(RENDERMODE_CONTINUOUSLY);}requestRender();}
    private void stop(){if(active||!continuous)return;continuous=false;setRenderMode(RENDERMODE_WHEN_DIRTY);onPause();}
    @Override protected void onDetachedFromWindow(){active=false;continuous=false;renderer.setWantActive(false);super.onDetachedFromWindow();}
}
