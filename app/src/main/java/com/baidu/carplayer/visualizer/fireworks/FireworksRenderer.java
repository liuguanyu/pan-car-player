package com.baidu.carplayer.visualizer.fireworks;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.baidu.carplayer.visualizer.AudioAnalyzer;
import com.baidu.carplayer.visualizer.GlslLoader;
import com.baidu.carplayer.visualizer.VisualizerManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 香江烟花秀的 GLES 3.0 渲染器。
 *
 * 楼群和烟花各绘制两遍，通过镜像与波纹扰动生成倒影。
 */
public final class FireworksRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "FireworksGl";
    private static final int VERTS = 6;
    private static final int STRIDE = 8;

    public interface IdleListener { void onIdle(); }

    private volatile IdleListener idle;
    private volatile boolean active;
    private volatile VisualizerManager audioSource;
    private int audioEpoch = Integer.MIN_VALUE;

    private Context appContext;
    private boolean failed;
    private int w = 1, h = 1;

    private int skyProgram, buildingProgram, vao, instTex;
    private int uSkyHorizon, uSkyTime, uSkyGlow;
    private int uInst, uAspect, uHorizon, uFlip, uStretch, uTime;

    private int particleProgram, uBurst, uDetails, uView, uColor;
    private int uSkyEnergy, uWaterEnergy, uWaterline;
    private final FireworksShow show = new FireworksShow();
    private final FireworksShow.Audio audio = new FireworksShow.Audio();
    private final AudioAnalyzer analyzer = new AudioAnalyzer();
    private final float[] magnitudes = new float[128];
    private final float[] previous = new float[128];
    private final float[] meters = new float[16];
    private byte[] previousFft;
    private float waterEnergy;

    private final FireworksScene scene = new FireworksScene();
    private final float[] upload = new float[FireworksScene.MAX_BUILDINGS * STRIDE];
    private final FloatBuffer instances = ByteBuffer
            .allocateDirect(FireworksScene.MAX_BUILDINGS * STRIDE * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    private long lastNs;
    private float time;

    public void setContext(Context context) { appContext = context.getApplicationContext(); }
    public void setIdleListener(IdleListener l) { idle = l; }
    public void setWantActive(boolean v) { active = v; }
    public void setAudioSource(VisualizerManager source) { audioSource = source; }

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        failed = false;
        try {
            if (appContext == null) throw new IllegalStateException("no context");
            skyProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.fireworks_sky_vert,
                    com.baidu.carplayer.R.raw.fireworks_sky_frag);
            buildingProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.fireworks_building_vert,
                    com.baidu.carplayer.R.raw.fireworks_building_frag);
            particleProgram = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.fireworks_particle_vert,
                    com.baidu.carplayer.R.raw.fireworks_particle_frag);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader", e);
            failed = true;
            return;
        }
        uSkyHorizon = GLES30.glGetUniformLocation(skyProgram, "uHorizon");
        uSkyTime = GLES30.glGetUniformLocation(skyProgram, "uTime");
        uSkyGlow = GLES30.glGetUniformLocation(skyProgram, "uGlowColor");
        uInst = GLES30.glGetUniformLocation(buildingProgram, "uInst");
        uAspect = GLES30.glGetUniformLocation(buildingProgram, "uAspect");
        uHorizon = GLES30.glGetUniformLocation(buildingProgram, "uHorizon");
        uFlip = GLES30.glGetUniformLocation(buildingProgram, "uFlip");
        uStretch = GLES30.glGetUniformLocation(buildingProgram, "uStretch");
        uTime = GLES30.glGetUniformLocation(buildingProgram, "uTime");

        uSkyEnergy = GLES30.glGetUniformLocation(skyProgram, "uWaterEnergy");
        uWaterEnergy = GLES30.glGetUniformLocation(buildingProgram, "uWaterEnergy");
        uWaterline = GLES30.glGetUniformLocation(buildingProgram, "uWaterline");
        uBurst = GLES30.glGetUniformLocation(particleProgram, "uBurst");
        uDetails = GLES30.glGetUniformLocation(particleProgram, "uDetails");
        uView = GLES30.glGetUniformLocation(particleProgram, "uView");
        uColor = GLES30.glGetUniformLocation(particleProgram, "uColor");

        int[] ids = new int[1];
        GLES30.glGenVertexArrays(1, ids, 0);
        vao = ids[0];
        GLES30.glGenTextures(1, ids, 0);
        instTex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, instTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
                FireworksScene.MAX_BUILDINGS * 2, 1, 0,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, null);
        scene.generate(20260101L);
    }

    @Override public void onSurfaceChanged(GL10 unused, int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        GLES30.glViewport(0, 0, w, h);
        scene.setAspect(h / (float) w);
        show.setAspect(scene.aspect());
        show.reset();
    }

    @Override public void onDrawFrame(GL10 unused) {
        if (failed) return;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? .016f : Math.min(.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (active) {
            time += dt;
            updateAudio(dt);
            show.update(dt, audio);
        }

        GLES30.glClearColor(.012f, .018f, .032f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        drawSky();
        int count = packBuildings();
        drawBuildings(count, true);
        drawFireworks(true);
        drawBuildings(count, false);
        drawFireworks(false);

        if (!active && idle != null) idle.onIdle();
    }

    private void updateAudio(float dt) {
        VisualizerManager source = audioSource;
        if (source == null) return;
        int epoch = source.epoch();
        if (epoch != audioEpoch) {
            audioEpoch = epoch;
            previousFft = null;
            java.util.Arrays.fill(previous, 0f);
            analyzer.reset();
            show.reset();
        }
        byte[] fft = source.latestFft();
        int count = AudioAnalyzer.decodeMagnitudes(fft, magnitudes);
        audio.bass = AudioAnalyzer.average(magnitudes, 1, Math.min(6, count));
        audio.mid = AudioAnalyzer.average(magnitudes, 6, Math.min(32, count));
        audio.treble = AudioAnalyzer.average(magnitudes, 32, count);
        audio.volume = AudioAnalyzer.average(magnitudes, 1, count);
        audio.flux = 0f;
        if (fft != previousFft) {
            float flux = 0f, sum = 0f, weighted = 0f;
            for (int i = 1; i < count; i++) {
                float value = magnitudes[i];
                flux += Math.max(0f, value - previous[i]);
                previous[i] = value;
                sum += value;
                weighted += value * i;
            }
            if (previousFft != null) audio.flux = Math.min(1f, flux * .28f);
            audio.centroid = sum > 0f ? Math.min(1f, weighted / (64f * sum)) : 0f;
            audio.peak = analyzer.detectPeak(audio.volume);
            previousFft = fft;
        }
        long now = android.os.SystemClock.uptimeMillis();
        audio.beatPosition = source.beatPosition(now);
        audio.beatConfidence = source.beatConfidence(now);
        float smoothing = Math.min(1f, dt * 7f);
        waterEnergy += (Math.min(1f, audio.bass * 4f) - waterEnergy) * smoothing;
        for (int i = 0; i < meters.length; i++) {
            int lo = 1 + (i * i * 127) / 256;
            int hi = 1 + ((i + 1) * (i + 1) * 127) / 256;
            float value = AudioAnalyzer.average(magnitudes, lo, Math.min(count, Math.max(lo + 1, hi)));
            meters[i] += (Math.min(1f, value * 5f) - meters[i]) * smoothing;
        }
    }

    private void drawFireworks(boolean reflection) {
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        int waterPixels = Math.round(h * (1f - FireworksScene.HORIZON_RATIO));
        GLES30.glScissor(0, reflection ? 0 : waterPixels, w,
                reflection ? waterPixels : h - waterPixels);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        GLES30.glUseProgram(particleProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glUniform4f(uView, scene.aspect(), scene.horizonY(), reflection ? 1f : 0f, .34f);
        float[] data = show.data();
        for (int slot = 0; slot < show.used(); slot++) {
            int i = slot * FireworksShow.FLOATS_PER_BURST;
            float age = show.time() - data[i + FireworksShow.F_SHELL_TIME];
            float duration = data[i + FireworksShow.F_DURATION];
            if (age >= FireworksShow.RISE_TIME + duration + .09f) continue;
            GLES30.glUniform4f(uBurst, data[i], data[i + 1], data[i + 2], age);
            GLES30.glUniform4f(uDetails, data[i + FireworksShow.F_SEED], data[i + FireworksShow.F_TYPE],
                    duration, FireworksShow.RISE_TIME);
            GLES30.glUniform3f(uColor, data[i + FireworksShow.F_R], data[i + FireworksShow.F_G],
                    data[i + FireworksShow.F_B]);
            int particles = age < FireworksShow.RISE_TIME ? 9 : (int) data[i + FireworksShow.F_PARTICLES];
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, VERTS, particles * 3);
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawSky() {
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(skyProgram);
        GLES30.glBindVertexArray(vao);
        float horizon = FireworksScene.HORIZON_RATIO;
        GLES30.glUniform1f(uSkyHorizon, horizon);
        GLES30.glUniform1f(uSkyTime, time);
        GLES30.glUniform1f(uSkyEnergy, waterEnergy);
        // 暖色光污染：靠近地平线的城市辉光
        GLES30.glUniform3f(uSkyGlow, .32f, .21f, .14f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private int packBuildings() {
        int n = 0;
        FireworksScene.Building[] list = scene.buildings();
        for (int i = 0; i < scene.count(); i++) {
            FireworksScene.Building b = list[i];
            // x, y(未用), halfW, height
            put(n++, b.x, 0f, b.halfW, b.height);
            // layer + style/4 打包, seed, 预留
            int band = Math.min(meters.length - 1, (int) (b.x * meters.length));
            put(n++, b.layer + b.style * .25f, b.seed, meters[band], 0f);
        }
        return scene.count();
    }

    private void put(int index, float a, float b, float c, float d) {
        int i = index * 4;
        upload[i] = a;
        upload[i + 1] = b;
        upload[i + 2] = c;
        upload[i + 3] = d;
    }

    private void drawBuildings(int count, boolean reflection) {
        if (count <= 0) return;
        GLES30.glEnable(GLES30.GL_BLEND);
        if (reflection) {
            // 倒影叠加在水面上，用加法混合让亮窗自然晕开
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        } else {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }
        GLES30.glUseProgram(buildingProgram);
        GLES30.glBindVertexArray(vao);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, instTex);
        GLES30.glUniform1i(uInst, 0);
        GLES30.glUniform1f(uAspect, scene.aspect());
        GLES30.glUniform1f(uHorizon, scene.horizonY());
        GLES30.glUniform1f(uFlip, reflection ? 1f : 0f);
        GLES30.glUniform1f(uStretch, 1.35f);
        GLES30.glUniform1f(uTime, time);
        GLES30.glUniform1f(uWaterEnergy, waterEnergy);
        GLES30.glUniform1f(uWaterline, FireworksScene.HORIZON_RATIO);
        instances.clear();
        instances.put(upload, 0, count * STRIDE);
        instances.flip();
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, count * 2, 1,
                GLES30.GL_RGBA, GLES30.GL_FLOAT, instances);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, VERTS, count);
        GLES30.glDisable(GLES30.GL_BLEND);
    }
}
