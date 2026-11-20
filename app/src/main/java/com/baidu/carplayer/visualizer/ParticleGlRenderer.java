package com.baidu.carplayer.visualizer;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 粒子可视化的 GLES 3.0 渲染器。GL 线程只做：读取 {@link ParticleScene} 的输出并绘制。
 *
 * <p>FFT 由 UI 线程（{@link VisualizerManager}）写入 {@link #setFft}（volatile），
 * GL 线程每帧取最新值推进场景；场景对象只在 GL 线程里被修改，无需加锁。
 */
public final class ParticleGlRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "ParticleGl";
    private static final byte[] EMPTY = new byte[0];

    /** 节拍周期来源，可为空（按 120 BPM）。 */
    public interface TempoSource { float periodMs(); }

    /** 最新频谱来源：播放中返回采集到的 FFT，否则返回空数组（让场景自然回落）。 */
    public interface FftSource { byte[] latest(); int epoch(); }

    /** 场景回落到静止且视图已不再需要动画时回调（GL 线程），视图据此停帧。 */
    public interface IdleListener { void onIdle(); }

    /** 每环圆周点数（原 ParticleShaders.DOTS，已随 shader 迁移内联到 raw 资源）。 */
    private static final int RING_DOTS = 240;
    private Context appContext;
    private final ParticleScene scene = new ParticleScene();
    private volatile FftSource fftSource;
    private volatile TempoSource tempo;
    private volatile IdleListener idleListener;
    private volatile boolean wantActive;
    private volatile boolean failed;
    private int seenEpoch = Integer.MIN_VALUE;

    private int bgProg, ringProg, axisProg, glowProg;
    private int width = 1, height = 1;
    private long lastNs;
    private float time;

    // uniform 位置
    private int uBgCenter, uBgGlow, uBgAspect, uBgTime;
    private int uRVP, uRA, uRH0, uRH1, uRL0, uRL1, uRTime, uRPulse, uRLevel, uRHeadX, uRHeadVis, uRPx, uRLife, uREnergy;
    private int uAVP, uAA, uAB, uAHalfW, uACam, uAAlpha, uASoft, uACurve, uACurvePhase;
    private int uRCurve, uRCurvePhase, uRColor;
    private int uAColor, uGColor;
    private int uGVP, uGPos, uGSize, uGAlpha;
    private int emptyVao;

    private final float[] tmp = new float[4];

    /** 由 View 在 setRenderer 前注入，用于读取 res/raw 里的 GLSL。 */
    public void setContext(Context context) { appContext = context.getApplicationContext(); }

    public void setFftSource(FftSource source) { fftSource = source; }
    public void setTempoSource(TempoSource source) { tempo = source; }
    public void setIdleListener(IdleListener l) { idleListener = l; }
    /** 视图是否仍需要动画（播放中为 true）。 */
    public void setWantActive(boolean value) { wantActive = value; }
    public boolean hasFailed() { return failed; }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            if (appContext == null) throw new IllegalStateException("no context");
            bgProg = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.particle_bg_vert, com.baidu.carplayer.R.raw.particle_bg_frag);
            ringProg = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.particle_ring_vert, com.baidu.carplayer.R.raw.particle_ring_frag);
            axisProg = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.particle_axis_vert, com.baidu.carplayer.R.raw.particle_axis_frag);
            glowProg = GlslLoader.program(appContext,
                    com.baidu.carplayer.R.raw.particle_glow_vert, com.baidu.carplayer.R.raw.particle_glow_frag);
        } catch (RuntimeException e) {
            Log.e(TAG, "着色器编译失败，粒子效果不可用", e);
            failed = true;
            return;
        }
        uBgCenter = GLES30.glGetUniformLocation(bgProg, "uCenter");
        uBgGlow = GLES30.glGetUniformLocation(bgProg, "uGlow");
        uBgAspect = GLES30.glGetUniformLocation(bgProg, "uAspect");
        uBgTime = GLES30.glGetUniformLocation(bgProg, "uTime");

        uRVP = GLES30.glGetUniformLocation(ringProg, "uVP");
        uRA = GLES30.glGetUniformLocation(ringProg, "uRingA");
        uRH0 = GLES30.glGetUniformLocation(ringProg, "uRingH0");
        uRH1 = GLES30.glGetUniformLocation(ringProg, "uRingH1");
        uRL0 = GLES30.glGetUniformLocation(ringProg, "uLive0");
        uRL1 = GLES30.glGetUniformLocation(ringProg, "uLive1");
        uRTime = GLES30.glGetUniformLocation(ringProg, "uTime");
        uRPulse = GLES30.glGetUniformLocation(ringProg, "uPulse");
        uRLevel = GLES30.glGetUniformLocation(ringProg, "uLevel");
        uRHeadX = GLES30.glGetUniformLocation(ringProg, "uHeadX");
        uRHeadVis = GLES30.glGetUniformLocation(ringProg, "uHeadVis");
        uRPx = GLES30.glGetUniformLocation(ringProg, "uPx");
        uRLife = GLES30.glGetUniformLocation(ringProg, "uLife");
        uREnergy = GLES30.glGetUniformLocation(ringProg, "uEnergy");
        uRCurve = GLES30.glGetUniformLocation(ringProg, "uCurve");
        uRCurvePhase = GLES30.glGetUniformLocation(ringProg, "uCurvePhase");
        uRColor = GLES30.glGetUniformLocation(ringProg, "uRingColor");

        uAVP = GLES30.glGetUniformLocation(axisProg, "uVP");
        uAA = GLES30.glGetUniformLocation(axisProg, "uA");
        uAB = GLES30.glGetUniformLocation(axisProg, "uB");
        uAHalfW = GLES30.glGetUniformLocation(axisProg, "uHalfW");
        uACam = GLES30.glGetUniformLocation(axisProg, "uCam");
        uAAlpha = GLES30.glGetUniformLocation(axisProg, "uAlpha");
        uASoft = GLES30.glGetUniformLocation(axisProg, "uSoft");
        uACurve = GLES30.glGetUniformLocation(axisProg, "uCurve");
        uACurvePhase = GLES30.glGetUniformLocation(axisProg, "uCurvePhase");
        uAColor = GLES30.glGetUniformLocation(axisProg, "uAxisColor");

        uGVP = GLES30.glGetUniformLocation(glowProg, "uVP");
        uGPos = GLES30.glGetUniformLocation(glowProg, "uPos");
        uGSize = GLES30.glGetUniformLocation(glowProg, "uSize");
        uGAlpha = GLES30.glGetUniformLocation(glowProg, "uAlpha");
        uGColor = GLES30.glGetUniformLocation(glowProg, "uGlowColor");

        // 核心配置里没有顶点属性也要绑定一个 VAO
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        emptyVao = vao[0];
        GLES30.glBindVertexArray(emptyVao);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        lastNs = 0L;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES30.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (failed) {
            GLES30.glClearColor(0.01f, 0.015f, 0.03f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016f : Math.min(0.05f, (now - lastNs) / 1e9f);
        lastNs = now;
        time += dt;
        FftSource fs = fftSource;
        byte[] fft = EMPTY;
        if (fs != null) {
            int epoch = fs.epoch();
            if (epoch != seenEpoch) { seenEpoch = epoch; scene.reset(); }
            byte[] latest = fs.latest();
            if (latest != null) fft = latest;
        }

        TempoSource src = tempo;
        float period = src != null ? src.periodMs() : 500f;
        float aspect = width / (float) height;
        scene.step(fft, dt, period, aspect);
        boolean atRest = scene.isAtRest();
        if (atRest && !wantActive) {
            IdleListener l = idleListener;
            if (l != null) l.onIdle();
        }

        GLES30.glBindVertexArray(emptyVao);
        GLES30.glViewport(0, 0, width, height);

        // 1) 背景（不混合）
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(bgProg);
        float[] c = projectToUv(scene.headPos);
        GLES30.glUniform2f(uBgCenter, c[0], c[1]);
        GLES30.glUniform1f(uBgGlow, 0.35f + 0.65f * scene.level);
        GLES30.glUniform1f(uBgAspect, aspect);
        GLES30.glUniform1f(uBgTime, time);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        if (scene.energy < 0.004f) return;

        // 2) 其余全部用加色混合（黑底白点，加色更通透）
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE);
        drawRings();
        drawAxis();
        drawHead();
    }

    private void drawRings() {
        GLES30.glUseProgram(ringProg);
        GLES30.glUniformMatrix4fv(uRVP, 1, false, scene.vp, 0);
        GLES30.glUniform4fv(uRA, ParticleScene.MAX_RINGS, scene.ringA, 0);
        GLES30.glUniform4fv(uRH0, ParticleScene.MAX_RINGS, scene.ringH0, 0);
        GLES30.glUniform4fv(uRH1, ParticleScene.MAX_RINGS, scene.ringH1, 0);
        GLES30.glUniform4fv(uRL0, 1, scene.live0, 0);
        GLES30.glUniform4fv(uRL1, 1, scene.live1, 0);
        GLES30.glUniform1f(uRTime, scene.time);
        GLES30.glUniform1f(uRPulse, scene.pulse);
        GLES30.glUniform1f(uRLevel, scene.level);
        GLES30.glUniform1f(uRHeadX, scene.headX);
        GLES30.glUniform1f(uRHeadVis, scene.headVis);
        GLES30.glUniform1f(uRPx, height / 480f * 1.15f);
        GLES30.glUniform1f(uRLife, ParticleScene.LIFE);
        // 粒子环接近中性白，略带冷灰，和蓝灰拖尾区分
        GLES30.glUniform3f(uRColor, 0.88f, 0.90f, 0.96f);
        GLES30.glUniform1f(uREnergy, scene.energy * 0.72f);
        GLES30.glUniform1f(uRCurve, scene.curveAmp);
        GLES30.glUniform1f(uRCurvePhase, scene.curvePhase);
        GLES30.glDrawArraysInstanced(GLES30.GL_POINTS, 0, RING_DOTS, ParticleScene.MAX_RINGS);
    }

    /** 光轴：实心粗白条 + 外层柔光；彗尾：沿轴向远端渐隐渐细。 */
    private void drawAxis() {
        GLES30.glUseProgram(axisProg);
        GLES30.glUniformMatrix4fv(uAVP, 1, false, scene.vp, 0);
        GLES30.glUniform3fv(uACam, 1, scene.camPos, 0);
        GLES30.glUniform1f(uACurve, scene.curveAmp);
        GLES30.glUniform1f(uACurvePhase, scene.curvePhase);
        float e = scene.energy;
        GLES30.glUniform3f(uAColor, 0.68f, 0.75f, 0.88f);
        float halfW = 0.015f * (0.85f + 0.5f * scene.level);

        // 外层柔光
        axisQuad(scene.axisA[0], scene.axisB[0], halfW * 3.2f, halfW * 3.2f, 0.035f * e, 0.035f * e, 1f);
        // 实心白条：近端略粗，远端略细，亮度整条偏高
        axisQuad(scene.axisA[0], scene.axisB[0], halfW * 1.05f, halfW * 0.78f, 0.55f * e, 0.55f * e, 0f);

        // 彗尾：从彗头沿 +X（身后）延伸，渐隐渐细
        if (scene.headVis > 0.02f) {
            float xa = scene.clampToView(Math.min(scene.axisA[0], scene.headX + scene.tailLen));
            axisQuad(xa, scene.headX, halfW * 0.45f, halfW * 1.7f, 0f, 0.26f * scene.headVis, 1f);
            axisQuad(xa, scene.headX, halfW * 0.22f, halfW * 1.0f, 0f, 0.42f * scene.headVis, 0f);
        }
    }

    private void axisQuad(float xA, float xB, float hwA, float hwB, float alphaA, float alphaB, float soft) {
        GLES30.glUniform3f(uAA, xA, 0f, 0f);
        GLES30.glUniform3f(uAB, xB, 0f, 0f);
        GLES30.glUniform2f(uAHalfW, hwA, hwB);
        GLES30.glUniform2f(uAAlpha, alphaA, alphaB);
        GLES30.glUniform1f(uASoft, soft);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 64);
    }

    private void drawHead() {
        if (scene.headVis < 0.02f) return;
        GLES30.glUseProgram(glowProg);
        GLES30.glUniformMatrix4fv(uGVP, 1, false, scene.vp, 0);
        GLES30.glUniform3fv(uGPos, 1, scene.headPos, 0);
        float glow = Math.min(1f, 0.45f * scene.level + 0.55f * Math.min(1f, scene.pulse));
        float sizeY = 0.07f + 0.10f * glow;
        GLES30.glUniform2f(uGSize, sizeY * height / (float) width, sizeY);
        GLES30.glUniform3f(uGColor, 0.92f, 0.96f, 1.0f);
        GLES30.glUniform1f(uGAlpha, scene.headVis * (0.55f + 0.20f * glow));
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** 光轴中点投影到屏幕 uv（辉光中心）。 */
    private float[] projectToUv(float[] p) {
        float[] m = scene.vp;
        float cx = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
        float cy = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
        float cw = m[3] * p[0] + m[7] * p[1] + m[11] * p[2] + m[15];
        if (cw < 0.01f) { tmp[0] = 0.5f; tmp[1] = 0.5f; return tmp; }
        tmp[0] = (cx / cw) * 0.5f + 0.5f;
        tmp[1] = (cy / cw) * 0.5f + 0.5f;
        return tmp;
    }

    // ---------- GL 工具 ----------

    private static int link(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetProgramInfoLog(p);
            GLES30.glDeleteProgram(p);
            throw new RuntimeException("link: " + log);
        }
        GLES30.glDeleteShader(v);
        GLES30.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            GLES30.glDeleteShader(s);
            throw new RuntimeException((type == GLES30.GL_VERTEX_SHADER ? "vert: " : "frag: ") + log);
        }
        return s;
    }

    @SuppressWarnings("unused")
    private static FloatBuffer floats(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }
}
